#!/usr/bin/env python3
"""Fine-grained terrain build/upload ratio hill climb after pressure tuning.

The preceding pressure phase supplies fixed constrained/severe multipliers.
This phase starts at the coarse ratio winner and searches a smaller 0.01 axis
neighborhood. Every point runs in a fresh production Minecraft process and
must report real adaptive scheduler frames.
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


def normalize(value: float) -> float:
    return round(value + 0.0, 4)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--metallum-jar", type=Path, required=True)
    parser.add_argument("--bench-project", type=Path, default=Path(".github/ci/terrain-bench"))
    parser.add_argument("--threads", type=int, required=True)
    parser.add_argument("--start-build-ratio", type=float, required=True)
    parser.add_argument("--start-upload-ratio", type=float, required=True)
    parser.add_argument("--constrained-multiplier", type=float, required=True)
    parser.add_argument("--severe-multiplier", type=float, required=True)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--step", type=float, default=0.01)
    parser.add_argument("--min-ratio", type=float, default=0.02)
    parser.add_argument("--max-ratio", type=float, default=0.20)
    parser.add_argument("--max-moves", type=int, default=3)
    parser.add_argument("--min-improvement", type=float, default=0.015)
    parser.add_argument("--tail-regression-limit", type=float, default=0.05)
    parser.add_argument("--output", type=Path, default=Path("build/terrain-scheduler-fine-hillclimb/report.json"))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.threads < 1:
        raise SystemExit("--threads must be >= 1")
    if args.repeats < 1:
        raise SystemExit("--repeats must be >= 1")
    if not 0.0 < args.step <= 0.10:
        raise SystemExit("--step must be in (0, 0.10]")
    if not 0.0 < args.min_ratio <= args.max_ratio <= 0.50:
        raise SystemExit("invalid ratio bounds")
    if not 0.10 <= args.severe_multiplier <= args.constrained_multiplier <= 1.00:
        raise SystemExit("pressure multipliers must satisfy 0.10 <= severe <= constrained <= 1.00")

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
            str(root / "gradlew"), "--no-daemon", "--build-cache", "--stacktrace",
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
                f"-PterrainAdaptiveSchedulingConstrainedMultiplier={args.constrained_multiplier:.4f}",
                f"-PterrainAdaptiveSchedulingSevereMultiplier={args.severe_multiplier:.4f}",
            ])
        command.append("runProductionTerrainBenchmark")

        started = time.perf_counter()
        completed = subprocess.run(command, cwd=root)
        wall_seconds = time.perf_counter() - started
        if completed.returncode != 0:
            raise RuntimeError(
                f"fine scheduler benchmark failed adaptive={adaptive} build={build_ratio} "
                f"upload={upload_ratio} repeat={repeat} exit={completed.returncode}"
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
        scheduler_enabled = bool(runtime.get("terrainSchedulerEnabled", False))
        scheduler_frames = int(runtime.get("terrainSchedulerFrames", 0))
        adaptive_frames = int(runtime.get("terrainSchedulerAdaptiveFrames", 0))
        pressure_frames = int(runtime.get("terrainSchedulerPressureFrames", 0))
        if ticks <= 0 or elapsed_ms <= 0.0:
            raise RuntimeError(f"invalid timing ticks={ticks} elapsedMs={elapsed_ms}")
        if adaptive and (not scheduler_enabled or adaptive_frames <= 0):
            raise RuntimeError(
                "fine adaptive candidate produced no adaptive frames: "
                f"enabled={scheduler_enabled} frames={scheduler_frames} adaptive={adaptive_frames}"
            )
        if not adaptive and scheduler_enabled:
            raise RuntimeError("disabled control unexpectedly reports scheduler enabled")

        observed_workers: list[int] = []
        if latest_log.is_file():
            observed_workers = [int(v) for v in WORKER_RE.findall(
                latest_log.read_text(encoding="utf-8", errors="replace")
            )]
        if not observed_workers or any(v != args.threads for v in observed_workers):
            raise RuntimeError(f"requested {args.threads} workers but observed {observed_workers}")
        return {
            "repeat": repeat,
            "chunkRenderTicks": ticks,
            "chunkRenderElapsedMillis": elapsed_ms,
            "wallSeconds": round(wall_seconds, 6),
            "schedulerFrames": scheduler_frames,
            "adaptiveFrames": adaptive_frames,
            "pressureFrames": pressure_frames,
            "pressureFrameFraction": pressure_frames / adaptive_frames if adaptive_frames else 0.0,
            "observedWorkerCounts": observed_workers,
        }

    def summarize(samples: list[dict[str, Any]]) -> dict[str, Any]:
        elapsed = [float(s["chunkRenderElapsedMillis"]) for s in samples]
        ticks = [float(s["chunkRenderTicks"]) for s in samples]
        return {
            "samples": samples,
            "medianChunkRenderElapsedMillis": statistics.median(elapsed),
            "p95ChunkRenderElapsedMillis": percentile(elapsed, 0.95),
            "medianChunkRenderTicks": statistics.median(ticks),
            "p95ChunkRenderTicks": percentile(ticks, 0.95),
            "medianAdaptiveFrames": statistics.median([float(s["adaptiveFrames"]) for s in samples]),
            "medianPressureFrameFraction": statistics.median([float(s["pressureFrameFraction"]) for s in samples]),
        }

    control_samples: list[dict[str, Any]] = []
    for repeat in range(1, args.repeats + 1):
        sample = launch(adaptive=False, build_ratio=None, upload_ratio=None, repeat=repeat)
        control_samples.append(sample)
        print(
            f"terrain-fine control repeat={repeat}/{args.repeats} ticks={sample['chunkRenderTicks']} "
            f"elapsed={sample['chunkRenderElapsedMillis']:.3f}ms", flush=True
        )
    control = summarize(control_samples)

    def evaluate(build_ratio: float, upload_ratio: float) -> dict[str, Any]:
        key = (normalize(build_ratio), normalize(upload_ratio))
        cached = results.get(key)
        if cached is not None:
            return cached
        samples: list[dict[str, Any]] = []
        for repeat in range(1, args.repeats + 1):
            sample = launch(adaptive=True, build_ratio=key[0], upload_ratio=key[1], repeat=repeat)
            samples.append(sample)
            print(
                f"terrain-fine build={key[0]:.4f} upload={key[1]:.4f} "
                f"repeat={repeat}/{args.repeats} ticks={sample['chunkRenderTicks']} "
                f"elapsed={sample['chunkRenderElapsedMillis']:.3f}ms "
                f"pressure={sample['pressureFrames']}/{sample['adaptiveFrames']}", flush=True
            )
        summary = summarize(samples)
        summary["buildBudgetRatio"] = key[0]
        summary["uploadBudgetRatio"] = key[1]
        results[key] = summary
        return summary

    current_key = (normalize(args.start_build_ratio), normalize(args.start_upload_ratio))
    baseline = evaluate(*current_key)
    path = [{"buildBudgetRatio": current_key[0], "uploadBudgetRatio": current_key[1]}]
    decisions: list[dict[str, Any]] = []

    for _move in range(args.max_moves):
        current = evaluate(*current_key)
        build_ratio, upload_ratio = current_key
        neighbors = {
            (normalize(build_ratio - args.step), normalize(upload_ratio)),
            (normalize(build_ratio + args.step), normalize(upload_ratio)),
            (normalize(build_ratio), normalize(upload_ratio - args.step)),
            (normalize(build_ratio), normalize(upload_ratio + args.step)),
        }
        neighbors = {
            p for p in neighbors
            if args.min_ratio <= p[0] <= args.max_ratio
            and args.min_ratio <= p[1] <= args.max_ratio
            and p != current_key
        }
        if not neighbors:
            break
        candidates = [evaluate(*p) for p in sorted(neighbors)]
        best = min(candidates, key=lambda r: (
            r["medianChunkRenderElapsedMillis"], r["medianChunkRenderTicks"],
            r["p95ChunkRenderElapsedMillis"], r["buildBudgetRatio"], r["uploadBudgetRatio"]
        ))
        current_median = float(current["medianChunkRenderElapsedMillis"])
        best_median = float(best["medianChunkRenderElapsedMillis"])
        improvement = (current_median - best_median) / current_median
        tail_ok = float(best["p95ChunkRenderElapsedMillis"]) <= (
            float(current["p95ChunkRenderElapsedMillis"]) * (1.0 + args.tail_regression_limit)
        )
        candidate_key = (float(best["buildBudgetRatio"]), float(best["uploadBudgetRatio"]))
        accepted = improvement >= args.min_improvement and tail_ok
        decisions.append({
            "from": {"buildBudgetRatio": current_key[0], "uploadBudgetRatio": current_key[1]},
            "candidate": {"buildBudgetRatio": candidate_key[0], "uploadBudgetRatio": candidate_key[1]},
            "medianElapsedImprovement": improvement,
            "tailAccepted": tail_ok,
            "accepted": accepted,
        })
        if not accepted:
            break
        current_key = candidate_key
        path.append({"buildBudgetRatio": current_key[0], "uploadBudgetRatio": current_key[1]})

    winner = evaluate(*current_key)
    baseline_median = float(baseline["medianChunkRenderElapsedMillis"])
    winner_median = float(winner["medianChunkRenderElapsedMillis"])
    control_median = float(control["medianChunkRenderElapsedMillis"])
    report = {
        "schema": 1,
        "objective": "fine-grained adaptive terrain build/upload budget ratio hill climb",
        "machine": {"detectedLogicalProcessors": max(1, os.cpu_count() or 1)},
        "fixed": {
            "sodiumWorkerCount": args.threads,
            "warmupFrames": 0,
            "constrainedMultiplier": args.constrained_multiplier,
            "severeMultiplier": args.severe_multiplier,
        },
        "controlAdaptiveDisabled": control,
        "search": {
            "algorithm": "two-dimensional axis-neighbor fine hill climb",
            "repeatsPerCandidate": args.repeats,
            "ratioStep": args.step,
            "ratioBounds": [args.min_ratio, args.max_ratio],
            "maxMoves": args.max_moves,
            "minimumMedianImprovement": args.min_improvement,
            "tailRegressionLimit": args.tail_regression_limit,
            "startBuildBudgetRatio": args.start_build_ratio,
            "startUploadBudgetRatio": args.start_upload_ratio,
            "path": path,
            "decisions": decisions,
        },
        "candidates": [results[key] for key in sorted(results)],
        "winner": {
            "buildBudgetRatio": current_key[0],
            "uploadBudgetRatio": current_key[1],
            "medianChunkRenderElapsedMillis": winner_median,
            "p95ChunkRenderElapsedMillis": winner["p95ChunkRenderElapsedMillis"],
            "medianChunkRenderTicks": winner["medianChunkRenderTicks"],
            "medianAdaptiveFrames": winner["medianAdaptiveFrames"],
            "medianPressureFrameFraction": winner["medianPressureFrameFraction"],
            "relativeMedianImprovementVsFineStart": (baseline_median - winner_median) / baseline_median,
            "relativeMedianImprovementVsSchedulerDisabledControl": (control_median - winner_median) / control_median,
            "recommendedSystemProperties": [
                "-Dmetallum.opt.terrainAdaptiveScheduling=true",
                "-Dmetallum.opt.terrainAdaptiveSchedulingWarmupFrames=0",
                f"-Dmetallum.opt.terrainAdaptiveSchedulingBuildBudgetRatio={current_key[0]:.4f}",
                f"-Dmetallum.opt.terrainAdaptiveSchedulingUploadBudgetRatio={current_key[1]:.4f}",
                f"-Dmetallum.opt.terrainAdaptiveSchedulingConstrainedMultiplier={args.constrained_multiplier:.4f}",
                f"-Dmetallum.opt.terrainAdaptiveSchedulingSevereMultiplier={args.severe_multiplier:.4f}",
            ],
        },
    }
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2), flush=True)
    print(f"terrain fine scheduler hill-climb report: {output}", flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"terrain fine scheduler hill climb failed: {exc}", file=sys.stderr)
        raise
