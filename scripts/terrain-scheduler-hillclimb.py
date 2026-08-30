#!/usr/bin/env python3
"""Hill-climb MetalUniversal terrain scheduling budgets on one Apple Silicon host.

Worker count is fixed to the winner from the preceding Sodium chunk-builder
search. This phase enables MetalUniversal's adaptive terrain scheduler from the
first measured frame (warmup=0) and searches the build/upload frame-budget
ratios. Every candidate launches a fresh production Minecraft client and is
accepted only when the scheduler reports real adaptive frames.

The report is experimental evidence, not a shipping-policy writer. Default
MetalUniversal constants remain unchanged when no tuning properties are set.
"""

from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import time
from typing import Any

WORKER_RE = re.compile(r"Started\s+(\d+)\s+worker threads")
DEFAULT_BUILD_RATIO = 0.10
DEFAULT_UPLOAD_RATIO = 0.08


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return math.nan
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * fraction
    low = math.floor(position)
    high = math.ceil(position)
    if low == high:
        return ordered[low]
    weight = position - low
    return ordered[low] * (1.0 - weight) + ordered[high] * weight


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--metallum-jar", type=Path, required=True)
    parser.add_argument("--bench-project", type=Path,
                        default=Path(".github/ci/terrain-bench"))
    parser.add_argument("--threads", type=int, required=True,
                        help="fixed Sodium chunk-builder worker count")
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--step", type=float, default=0.02)
    parser.add_argument("--min-ratio", type=float, default=0.02)
    parser.add_argument("--max-ratio", type=float, default=0.20)
    parser.add_argument("--max-moves", type=int, default=4)
    parser.add_argument("--min-improvement", type=float, default=0.02)
    parser.add_argument("--tail-regression-limit", type=float, default=0.05)
    parser.add_argument("--output", type=Path,
                        default=Path("build/terrain-scheduler-hillclimb/report.json"))
    return parser.parse_args()


def normalize_ratio(value: float) -> float:
    return round(value + 0.0, 4)


def main() -> int:
    args = parse_args()
    if args.threads < 1:
        raise SystemExit("--threads must be >= 1")
    if args.repeats < 1:
        raise SystemExit("--repeats must be >= 1")
    if not 0.0 < args.step <= 0.25:
        raise SystemExit("--step must be in (0, 0.25]")
    if not 0.0 < args.min_ratio <= args.max_ratio <= 0.50:
        raise SystemExit("ratio bounds must satisfy 0 < min <= max <= 0.50")
    if args.max_moves < 0:
        raise SystemExit("--max-moves must be non-negative")
    if not 0.0 <= args.min_improvement < 1.0:
        raise SystemExit("--min-improvement must be in [0, 1)")

    root = args.repo_root.resolve()
    jar = args.metallum_jar.resolve()
    if not jar.is_file():
        raise SystemExit(f"MetalUniversal JAR not found: {jar}")

    bench = args.bench_project
    if not bench.is_absolute():
        bench = root / bench
    bench = bench.resolve()
    if not (bench / "build.gradle").is_file():
        raise SystemExit(f"terrain benchmark project not found: {bench}")

    evidence = bench / "build" / "evidence"
    runtime_json = evidence / "terrain-benchmark.json"
    latest_log = bench / "build" / "run" / "clientGameTest" / "logs" / "latest.log"
    output = args.output if args.output.is_absolute() else root / args.output
    output.parent.mkdir(parents=True, exist_ok=True)

    results: dict[tuple[float, float], dict[str, Any]] = {}

    def launch(*, adaptive: bool, build_ratio: float | None, upload_ratio: float | None,
               repeat: int) -> dict[str, Any]:
        shutil.rmtree(evidence, ignore_errors=True)
        command = [
            str(root / "gradlew"),
            "--no-daemon",
            "--build-cache",
            "--stacktrace",
            "-p", str(bench),
            f"-PmetallumJar={jar}",
            f"-PterrainChunkBuilderThreads={args.threads}",
            f"-PterrainAdaptiveScheduling={'true' if adaptive else 'false'}",
        ]
        if adaptive:
            command.extend([
                "-PterrainAdaptiveSchedulingWarmupFrames=0",
                f"-PterrainAdaptiveSchedulingBuildBudgetRatio={build_ratio:.4f}",
                f"-PterrainAdaptiveSchedulingUploadBudgetRatio={upload_ratio:.4f}",
            ])
        command.append("runProductionTerrainBenchmark")

        started = time.perf_counter()
        completed = subprocess.run(command, cwd=root)
        wall_seconds = time.perf_counter() - started
        if completed.returncode != 0:
            mode = "adaptive" if adaptive else "control"
            raise RuntimeError(
                f"terrain scheduler benchmark failed mode={mode} repeat={repeat} "
                f"with exit code {completed.returncode}"
            )
        if not runtime_json.is_file():
            raise RuntimeError(f"missing terrain evidence after benchmark: {runtime_json}")

        runtime = json.loads(runtime_json.read_text(encoding="utf-8"))
        if runtime.get("sodiumLoaded") is not True or runtime.get("chunksRendered") is not True:
            raise RuntimeError(f"invalid terrain runtime evidence: {runtime}")
        if str(runtime.get("backend", "")).lower() != "metal":
            raise RuntimeError(f"terrain benchmark did not use Metal: {runtime}")

        ticks = int(runtime["chunkRenderTicks"])
        elapsed_ms = float(runtime["chunkRenderElapsedMillis"])
        if ticks <= 0 or elapsed_ms <= 0.0:
            raise RuntimeError(f"invalid terrain timing: ticks={ticks} elapsedMillis={elapsed_ms}")

        scheduler_enabled = bool(runtime.get("terrainSchedulerEnabled", False))
        scheduler_frames = int(runtime.get("terrainSchedulerFrames", 0))
        adaptive_frames = int(runtime.get("terrainSchedulerAdaptiveFrames", 0))
        pressure_frames = int(runtime.get("terrainSchedulerPressureFrames", 0))
        if adaptive and (not scheduler_enabled or adaptive_frames <= 0):
            raise RuntimeError(
                "adaptive scheduler candidate produced no adaptive frames: "
                f"enabled={scheduler_enabled} frames={scheduler_frames} "
                f"adaptiveFrames={adaptive_frames}"
            )
        if not adaptive and scheduler_enabled:
            raise RuntimeError("disabled control unexpectedly reports scheduler enabled")

        observed_workers: list[int] = []
        if latest_log.is_file():
            observed_workers = [int(value) for value in WORKER_RE.findall(
                latest_log.read_text(encoding="utf-8", errors="replace")
            )]
        if not observed_workers or any(value != args.threads for value in observed_workers):
            raise RuntimeError(
                f"requested {args.threads} Sodium workers but observed {observed_workers}"
            )

        return {
            "repeat": repeat,
            "chunkRenderTicks": ticks,
            "chunkRenderElapsedMillis": elapsed_ms,
            "wallSeconds": round(wall_seconds, 6),
            "schedulerFrames": scheduler_frames,
            "adaptiveFrames": adaptive_frames,
            "pressureFrames": pressure_frames,
            "observedWorkerCounts": observed_workers,
        }

    def summarize(samples: list[dict[str, Any]]) -> dict[str, Any]:
        elapsed = [float(sample["chunkRenderElapsedMillis"]) for sample in samples]
        ticks = [float(sample["chunkRenderTicks"]) for sample in samples]
        return {
            "samples": samples,
            "medianChunkRenderElapsedMillis": statistics.median(elapsed),
            "p95ChunkRenderElapsedMillis": percentile(elapsed, 0.95),
            "medianChunkRenderTicks": statistics.median(ticks),
            "p95ChunkRenderTicks": percentile(ticks, 0.95),
            "medianAdaptiveFrames": statistics.median(
                [float(sample["adaptiveFrames"]) for sample in samples]
            ),
            "medianPressureFrames": statistics.median(
                [float(sample["pressureFrames"]) for sample in samples]
            ),
        }

    control_samples = []
    for repeat in range(1, args.repeats + 1):
        sample = launch(adaptive=False, build_ratio=None, upload_ratio=None, repeat=repeat)
        control_samples.append(sample)
        print(
            f"terrain-scheduler control threads={args.threads} repeat={repeat}/{args.repeats} "
            f"ticks={sample['chunkRenderTicks']} elapsed={sample['chunkRenderElapsedMillis']:.3f}ms",
            flush=True,
        )
    control = summarize(control_samples)

    def evaluate(build_ratio: float, upload_ratio: float) -> dict[str, Any]:
        key = (normalize_ratio(build_ratio), normalize_ratio(upload_ratio))
        cached = results.get(key)
        if cached is not None:
            return cached

        samples: list[dict[str, Any]] = []
        for repeat in range(1, args.repeats + 1):
            sample = launch(
                adaptive=True,
                build_ratio=key[0],
                upload_ratio=key[1],
                repeat=repeat,
            )
            samples.append(sample)
            print(
                f"terrain-scheduler build={key[0]:.4f} upload={key[1]:.4f} "
                f"repeat={repeat}/{args.repeats} ticks={sample['chunkRenderTicks']} "
                f"elapsed={sample['chunkRenderElapsedMillis']:.3f}ms "
                f"adaptiveFrames={sample['adaptiveFrames']} pressureFrames={sample['pressureFrames']}",
                flush=True,
            )

        summary = summarize(samples)
        summary["buildBudgetRatio"] = key[0]
        summary["uploadBudgetRatio"] = key[1]
        results[key] = summary
        return summary

    current_key = (DEFAULT_BUILD_RATIO, DEFAULT_UPLOAD_RATIO)
    baseline = evaluate(*current_key)
    path = [{"buildBudgetRatio": current_key[0], "uploadBudgetRatio": current_key[1]}]
    decisions: list[dict[str, Any]] = []

    for _move in range(args.max_moves):
        current = evaluate(*current_key)
        build_ratio, upload_ratio = current_key
        neighbors = {
            (normalize_ratio(build_ratio - args.step), normalize_ratio(upload_ratio)),
            (normalize_ratio(build_ratio + args.step), normalize_ratio(upload_ratio)),
            (normalize_ratio(build_ratio), normalize_ratio(upload_ratio - args.step)),
            (normalize_ratio(build_ratio), normalize_ratio(upload_ratio + args.step)),
        }
        neighbors = {
            point for point in neighbors
            if args.min_ratio <= point[0] <= args.max_ratio
            and args.min_ratio <= point[1] <= args.max_ratio
            and point != current_key
        }
        if not neighbors:
            break

        candidates = [evaluate(*point) for point in sorted(neighbors)]
        best = min(
            candidates,
            key=lambda result: (
                result["medianChunkRenderElapsedMillis"],
                result["medianChunkRenderTicks"],
                result["p95ChunkRenderElapsedMillis"],
                result["buildBudgetRatio"],
                result["uploadBudgetRatio"],
            ),
        )
        current_median = float(current["medianChunkRenderElapsedMillis"])
        best_median = float(best["medianChunkRenderElapsedMillis"])
        improvement = (current_median - best_median) / current_median
        current_p95 = float(current["p95ChunkRenderElapsedMillis"])
        best_p95 = float(best["p95ChunkRenderElapsedMillis"])
        tail_ok = best_p95 <= current_p95 * (1.0 + args.tail_regression_limit)
        accepted = improvement >= args.min_improvement and tail_ok

        candidate_key = (
            float(best["buildBudgetRatio"]),
            float(best["uploadBudgetRatio"]),
        )
        decisions.append({
            "from": {
                "buildBudgetRatio": current_key[0],
                "uploadBudgetRatio": current_key[1],
            },
            "candidate": {
                "buildBudgetRatio": candidate_key[0],
                "uploadBudgetRatio": candidate_key[1],
            },
            "medianElapsedImprovement": improvement,
            "tailAccepted": tail_ok,
            "accepted": accepted,
        })
        if not accepted:
            break

        current_key = candidate_key
        path.append({
            "buildBudgetRatio": current_key[0],
            "uploadBudgetRatio": current_key[1],
        })

    winner = evaluate(*current_key)
    baseline_median = float(baseline["medianChunkRenderElapsedMillis"])
    winner_median = float(winner["medianChunkRenderElapsedMillis"])
    control_median = float(control["medianChunkRenderElapsedMillis"])

    report = {
        "schema": 1,
        "objective": "minimize isolated Metal terrain readiness milliseconds with adaptive scheduling active",
        "scope": "MetalUniversal terrain scheduler build/upload budgets; Sodium worker count is fixed",
        "benchmarkProject": str(bench.relative_to(root)),
        "machine": {
            "detectedLogicalProcessors": max(1, os.cpu_count() or 1),
        },
        "fixedSodiumWorkerCount": args.threads,
        "controlAdaptiveDisabled": control,
        "search": {
            "algorithm": "two-dimensional axis-neighbor hill climb",
            "warmupFrames": 0,
            "repeatsPerCandidate": args.repeats,
            "ratioStep": args.step,
            "ratioBounds": [args.min_ratio, args.max_ratio],
            "maxMoves": args.max_moves,
            "minimumMedianImprovement": args.min_improvement,
            "tailRegressionLimit": args.tail_regression_limit,
            "defaultBuildBudgetRatio": DEFAULT_BUILD_RATIO,
            "defaultUploadBudgetRatio": DEFAULT_UPLOAD_RATIO,
            "path": path,
            "decisions": decisions,
        },
        "candidates": [
            results[key] for key in sorted(results)
        ],
        "winner": {
            "buildBudgetRatio": current_key[0],
            "uploadBudgetRatio": current_key[1],
            "medianChunkRenderElapsedMillis": winner_median,
            "p95ChunkRenderElapsedMillis": winner["p95ChunkRenderElapsedMillis"],
            "medianChunkRenderTicks": winner["medianChunkRenderTicks"],
            "medianAdaptiveFrames": winner["medianAdaptiveFrames"],
            "relativeMedianImprovementVsAdaptiveDefault": (
                (baseline_median - winner_median) / baseline_median
            ),
            "relativeMedianImprovementVsSchedulerDisabledControl": (
                (control_median - winner_median) / control_median
            ),
            "recommendedSystemProperties": [
                "-Dmetallum.opt.terrainAdaptiveScheduling=true",
                "-Dmetallum.opt.terrainAdaptiveSchedulingWarmupFrames=0",
                f"-Dmetallum.opt.terrainAdaptiveSchedulingBuildBudgetRatio={current_key[0]:.4f}",
                f"-Dmetallum.opt.terrainAdaptiveSchedulingUploadBudgetRatio={current_key[1]:.4f}",
            ],
        },
    }
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2), flush=True)
    print(f"terrain scheduler hill-climb report: {output}", flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"terrain scheduler hill climb failed: {exc}", file=sys.stderr)
        raise
