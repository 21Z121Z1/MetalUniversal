#!/usr/bin/env python3
"""Hill-climb MetalUniversal terrain pressure throttling on Apple Silicon.

This phase fixes the Sodium worker count and build/upload ratios chosen by the
preceding searches, then varies the pressure multipliers used by
TerrainSchedulingController.  A forced (1.0, 1.0) probe keeps pressure
detection active while removing its budget reduction, which distinguishes a
bad pressure signal from a bad adaptive base budget.

Every candidate launches a fresh production Minecraft client.  The script only
writes experimental evidence; production constants remain unchanged unless the
explicit tuning system properties are supplied.
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
DEFAULT_CONSTRAINED = 0.75
DEFAULT_SEVERE = 0.50


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
    parser.add_argument("--bench-project", type=Path, default=Path(".github/ci/terrain-bench"))
    parser.add_argument("--threads", type=int, required=True)
    parser.add_argument("--build-ratio", type=float, required=True)
    parser.add_argument("--upload-ratio", type=float, required=True)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--step", type=float, default=0.10)
    parser.add_argument("--max-moves", type=int, default=3)
    parser.add_argument("--min-improvement", type=float, default=0.02)
    parser.add_argument("--tail-regression-limit", type=float, default=0.05)
    parser.add_argument("--output", type=Path, default=Path("build/terrain-pressure-hillclimb/report.json"))
    return parser.parse_args()


def normalize(value: float) -> float:
    return round(value + 0.0, 4)


def main() -> int:
    args = parse_args()
    if args.threads < 1:
        raise SystemExit("--threads must be >= 1")
    if args.repeats < 1:
        raise SystemExit("--repeats must be >= 1")
    if not 0.0 < args.build_ratio <= 0.50 or not 0.0 < args.upload_ratio <= 0.50:
        raise SystemExit("build/upload ratios must be in (0, 0.50]")
    if not 0.0 < args.step <= 0.25:
        raise SystemExit("--step must be in (0, 0.25]")
    if args.max_moves < 0:
        raise SystemExit("--max-moves must be non-negative")

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

    def launch(*, adaptive: bool, constrained: float | None, severe: float | None,
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
                f"-PterrainAdaptiveSchedulingBuildBudgetRatio={args.build_ratio:.4f}",
                f"-PterrainAdaptiveSchedulingUploadBudgetRatio={args.upload_ratio:.4f}",
                f"-PterrainAdaptiveSchedulingConstrainedMultiplier={constrained:.4f}",
                f"-PterrainAdaptiveSchedulingSevereMultiplier={severe:.4f}",
            ])
        command.append("runProductionTerrainBenchmark")

        started = time.perf_counter()
        completed = subprocess.run(command, cwd=root)
        wall_seconds = time.perf_counter() - started
        if completed.returncode != 0:
            raise RuntimeError(
                f"pressure benchmark failed adaptive={adaptive} constrained={constrained} "
                f"severe={severe} repeat={repeat} exit={completed.returncode}"
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
                "adaptive pressure candidate produced no adaptive frames: "
                f"enabled={scheduler_enabled} frames={scheduler_frames} adaptive={adaptive_frames}"
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
        pressure_fraction = pressure_frames / adaptive_frames if adaptive_frames > 0 else 0.0
        return {
            "repeat": repeat,
            "chunkRenderTicks": ticks,
            "chunkRenderElapsedMillis": elapsed_ms,
            "wallSeconds": round(wall_seconds, 6),
            "schedulerFrames": scheduler_frames,
            "adaptiveFrames": adaptive_frames,
            "pressureFrames": pressure_frames,
            "pressureFrameFraction": pressure_fraction,
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
            "medianPressureFrames": statistics.median([float(s["pressureFrames"]) for s in samples]),
            "medianPressureFrameFraction": statistics.median([float(s["pressureFrameFraction"]) for s in samples]),
        }

    control_samples: list[dict[str, Any]] = []
    for repeat in range(1, args.repeats + 1):
        sample = launch(adaptive=False, constrained=None, severe=None, repeat=repeat)
        control_samples.append(sample)
        print(
            f"terrain-pressure control repeat={repeat}/{args.repeats} "
            f"ticks={sample['chunkRenderTicks']} elapsed={sample['chunkRenderElapsedMillis']:.3f}ms",
            flush=True,
        )
    control = summarize(control_samples)

    def evaluate(constrained: float, severe: float) -> dict[str, Any]:
        key = (normalize(constrained), normalize(severe))
        if key[1] > key[0]:
            raise ValueError(f"severe multiplier must not exceed constrained multiplier: {key}")
        cached = results.get(key)
        if cached is not None:
            return cached
        samples: list[dict[str, Any]] = []
        for repeat in range(1, args.repeats + 1):
            sample = launch(adaptive=True, constrained=key[0], severe=key[1], repeat=repeat)
            samples.append(sample)
            print(
                f"terrain-pressure constrained={key[0]:.4f} severe={key[1]:.4f} "
                f"repeat={repeat}/{args.repeats} ticks={sample['chunkRenderTicks']} "
                f"elapsed={sample['chunkRenderElapsedMillis']:.3f}ms "
                f"pressure={sample['pressureFrames']}/{sample['adaptiveFrames']}",
                flush=True,
            )
        summary = summarize(samples)
        summary["constrainedMultiplier"] = key[0]
        summary["severeMultiplier"] = key[1]
        results[key] = summary
        return summary

    baseline_key = (DEFAULT_CONSTRAINED, DEFAULT_SEVERE)
    baseline = evaluate(*baseline_key)
    current_key = baseline_key
    path = [{"constrainedMultiplier": current_key[0], "severeMultiplier": current_key[1]}]
    decisions: list[dict[str, Any]] = []

    for _move in range(args.max_moves):
        current = evaluate(*current_key)
        constrained, severe = current_key
        neighbors = {
            (normalize(constrained - args.step), normalize(severe)),
            (normalize(constrained + args.step), normalize(severe)),
            (normalize(constrained), normalize(severe - args.step)),
            (normalize(constrained), normalize(severe + args.step)),
        }
        neighbors = {
            p for p in neighbors
            if 0.10 <= p[1] <= p[0] <= 1.00 and p != current_key
        }
        if not neighbors:
            break
        candidates = [evaluate(*p) for p in sorted(neighbors)]
        best = min(candidates, key=lambda r: (
            r["medianChunkRenderElapsedMillis"], r["medianChunkRenderTicks"],
            r["p95ChunkRenderElapsedMillis"], -r["constrainedMultiplier"], -r["severeMultiplier"]
        ))
        current_median = float(current["medianChunkRenderElapsedMillis"])
        best_median = float(best["medianChunkRenderElapsedMillis"])
        improvement = (current_median - best_median) / current_median
        tail_ok = float(best["p95ChunkRenderElapsedMillis"]) <= (
            float(current["p95ChunkRenderElapsedMillis"]) * (1.0 + args.tail_regression_limit)
        )
        candidate_key = (float(best["constrainedMultiplier"]), float(best["severeMultiplier"]))
        accepted = improvement >= args.min_improvement and tail_ok
        decisions.append({
            "from": {"constrainedMultiplier": current_key[0], "severeMultiplier": current_key[1]},
            "candidate": {"constrainedMultiplier": candidate_key[0], "severeMultiplier": candidate_key[1]},
            "medianElapsedImprovement": improvement,
            "tailAccepted": tail_ok,
            "accepted": accepted,
        })
        if not accepted:
            break
        current_key = candidate_key
        path.append({"constrainedMultiplier": current_key[0], "severeMultiplier": current_key[1]})

    # Non-local causal probes. (1,1) leaves pressure detection/counters intact but
    # removes all pressure-induced budget reduction. The intermediate anchors help
    # with noisy/non-convex hosted-VM measurements without redefining the local path.
    diagnostic_points = [(0.85, 0.60), (0.90, 0.75), (1.00, 1.00)]
    for point in diagnostic_points:
        evaluate(*point)

    baseline_p95 = float(baseline["p95ChunkRenderElapsedMillis"])
    eligible = [
        result for result in results.values()
        if float(result["p95ChunkRenderElapsedMillis"]) <= baseline_p95 * (1.0 + args.tail_regression_limit)
    ]
    best_observed = min(eligible, key=lambda r: (
        r["medianChunkRenderElapsedMillis"], r["medianChunkRenderTicks"],
        r["p95ChunkRenderElapsedMillis"], -r["constrainedMultiplier"], -r["severeMultiplier"]
    ))
    observed_improvement = (
        float(baseline["medianChunkRenderElapsedMillis"]) - float(best_observed["medianChunkRenderElapsedMillis"])
    ) / float(baseline["medianChunkRenderElapsedMillis"])
    if observed_improvement >= args.min_improvement:
        winner = best_observed
    else:
        winner = baseline

    control_median = float(control["medianChunkRenderElapsedMillis"])
    baseline_median = float(baseline["medianChunkRenderElapsedMillis"])
    winner_median = float(winner["medianChunkRenderElapsedMillis"])
    no_throttle = results[(1.0, 1.0)]
    no_throttle_median = float(no_throttle["medianChunkRenderElapsedMillis"])

    report = {
        "schema": 1,
        "objective": "test and hill-climb adaptive terrain pressure budget multipliers",
        "scope": "MetalUniversal pressure response during initial terrain readiness; worker count and base ratios fixed",
        "machine": {"detectedLogicalProcessors": max(1, os.cpu_count() or 1)},
        "fixed": {
            "sodiumWorkerCount": args.threads,
            "warmupFrames": 0,
            "buildBudgetRatio": args.build_ratio,
            "uploadBudgetRatio": args.upload_ratio,
        },
        "controlAdaptiveDisabled": control,
        "search": {
            "algorithm": "two-dimensional axis-neighbor hill climb plus causal anchors",
            "repeatsPerCandidate": args.repeats,
            "step": args.step,
            "maxMoves": args.max_moves,
            "minimumMedianImprovement": args.min_improvement,
            "tailRegressionLimit": args.tail_regression_limit,
            "defaultConstrainedMultiplier": DEFAULT_CONSTRAINED,
            "defaultSevereMultiplier": DEFAULT_SEVERE,
            "path": path,
            "decisions": decisions,
            "diagnosticPoints": diagnostic_points,
        },
        "candidates": [results[key] for key in sorted(results)],
        "causalProbeNoPressureThrottle": {
            **no_throttle,
            "relativeMedianImprovementVsCurrentPressure": (baseline_median - no_throttle_median) / baseline_median,
            "relativeMedianImprovementVsSchedulerDisabledControl": (control_median - no_throttle_median) / control_median,
        },
        "winner": {
            "constrainedMultiplier": winner["constrainedMultiplier"],
            "severeMultiplier": winner["severeMultiplier"],
            "medianChunkRenderElapsedMillis": winner_median,
            "p95ChunkRenderElapsedMillis": winner["p95ChunkRenderElapsedMillis"],
            "medianChunkRenderTicks": winner["medianChunkRenderTicks"],
            "medianPressureFrameFraction": winner["medianPressureFrameFraction"],
            "relativeMedianImprovementVsCurrentPressure": (baseline_median - winner_median) / baseline_median,
            "relativeMedianImprovementVsSchedulerDisabledControl": (control_median - winner_median) / control_median,
            "recommendedSystemProperties": [
                f"-Dmetallum.opt.terrainAdaptiveSchedulingConstrainedMultiplier={winner['constrainedMultiplier']:.4f}",
                f"-Dmetallum.opt.terrainAdaptiveSchedulingSevereMultiplier={winner['severeMultiplier']:.4f}",
            ],
        },
    }
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2), flush=True)
    print(f"terrain pressure hill-climb report: {output}", flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"terrain pressure hill climb failed: {exc}", file=sys.stderr)
        raise
