#!/usr/bin/env python3
"""Hill-climb Sodium chunk-builder worker count on one Apple Silicon host.

Each candidate launches the isolated production terrain Client GameTest and
scores only Fabric's waitForChunksRender() readiness interval. The benchmark
project intentionally excludes framebuffer captures, resource reload tests,
Iris and unrelated presentation checks so the objective stays close to Sodium
terrain construction and upload readiness.

This is an experiment harness, not an automatic shipping-policy writer. It
emits a recommendation artifact; the production default remains Sodium's own
thread-count policy unless metallum.opt.terrainChunkBuilderThreads is set.
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

THREAD_PROPERTY = "metallum.opt.terrainChunkBuilderThreads"
ADAPTIVE_PROPERTY = "metallum.opt.terrainAdaptiveScheduling"
WORKER_RE = re.compile(r"Started\s+(\d+)\s+worker threads")


def sodium_default_thread_count(cpus: int) -> int:
    cpus = max(1, cpus)
    return max(1, min(10, max(cpus // 3, cpus - 6)))


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


def append_java_tool_option(existing: str | None, option: str) -> str:
    current = (existing or "").strip()
    return f"{current} {option}".strip()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--metallum-jar", type=Path, required=True)
    parser.add_argument("--bench-project", type=Path,
                        default=Path(".github/ci/terrain-bench"))
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--max-threads", type=int, default=0,
                        help="0 uses os.cpu_count(); positive values cap the search")
    parser.add_argument("--min-improvement", type=float, default=0.02,
                        help="minimum median chunkRenderTicks improvement to move uphill")
    parser.add_argument("--tail-regression-limit", type=float, default=0.05,
                        help="maximum allowed p95 regression while accepting a faster median")
    parser.add_argument("--output", type=Path,
                        default=Path("build/terrain-hillclimb/report.json"))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.repeats < 1:
        raise SystemExit("--repeats must be >= 1")
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

    detected_cpus = max(1, os.cpu_count() or 1)
    max_threads = detected_cpus if args.max_threads <= 0 else min(detected_cpus, args.max_threads)
    max_threads = max(1, max_threads)
    baseline_threads = min(sodium_default_thread_count(detected_cpus), max_threads)

    evidence = bench / "build" / "evidence"
    runtime_json = evidence / "terrain-benchmark.json"
    latest_log = bench / "build" / "run" / "clientGameTest" / "logs" / "latest.log"
    output = args.output if args.output.is_absolute() else root / args.output
    output.parent.mkdir(parents=True, exist_ok=True)

    results: dict[int, dict[str, Any]] = {}

    def evaluate(threads: int) -> dict[str, Any]:
        cached = results.get(threads)
        if cached is not None:
            return cached

        samples: list[dict[str, Any]] = []
        for repeat in range(1, args.repeats + 1):
            shutil.rmtree(evidence, ignore_errors=True)

            env = os.environ.copy()
            env["JAVA_TOOL_OPTIONS"] = append_java_tool_option(
                env.get("JAVA_TOOL_OPTIONS"), f"-D{THREAD_PROPERTY}={threads}"
            )
            # Isolate worker-count effects from MetalUniversal's existing dynamic
            # build/upload budget controller. That controller becomes a separate
            # search dimension after worker-count behavior is characterized.
            env["JAVA_TOOL_OPTIONS"] = append_java_tool_option(
                env.get("JAVA_TOOL_OPTIONS"), f"-D{ADAPTIVE_PROPERTY}=false"
            )

            command = [
                str(root / "gradlew"),
                "--no-daemon",
                "--build-cache",
                "--stacktrace",
                "-p", str(bench),
                f"-PmetallumJar={jar}",
                "runProductionTerrainBenchmark",
            ]
            started = time.perf_counter()
            completed = subprocess.run(command, cwd=root, env=env)
            wall_seconds = time.perf_counter() - started
            if completed.returncode != 0:
                raise RuntimeError(
                    f"terrain benchmark failed for threads={threads} repeat={repeat} "
                    f"with exit code {completed.returncode}"
                )
            if not runtime_json.is_file():
                raise RuntimeError(f"missing terrain evidence after benchmark: {runtime_json}")

            runtime = json.loads(runtime_json.read_text(encoding="utf-8"))
            if runtime.get("sodiumLoaded") is not True or runtime.get("chunksRendered") is not True:
                raise RuntimeError(f"invalid terrain runtime evidence: {runtime}")
            if str(runtime.get("backend", "")).lower() != "metal":
                raise RuntimeError(f"terrain benchmark did not use Metal: {runtime}")
            chunk_ticks = int(runtime["chunkRenderTicks"])
            elapsed_millis = float(runtime["chunkRenderElapsedMillis"])
            if chunk_ticks <= 0 or elapsed_millis <= 0.0:
                raise RuntimeError(
                    f"invalid terrain timing: ticks={chunk_ticks} elapsedMillis={elapsed_millis}"
                )

            observed_workers: list[int] = []
            if latest_log.is_file():
                observed_workers = [int(value) for value in WORKER_RE.findall(
                    latest_log.read_text(encoding="utf-8", errors="replace")
                )]
            if not observed_workers:
                raise RuntimeError("Sodium worker-count log evidence was not found")
            if any(value != threads for value in observed_workers):
                raise RuntimeError(
                    f"requested {threads} Sodium workers but observed {observed_workers}"
                )

            sample = {
                "repeat": repeat,
                "chunkRenderTicks": chunk_ticks,
                "chunkRenderElapsedMillis": elapsed_millis,
                "wallSeconds": round(wall_seconds, 6),
                "observedWorkerCounts": observed_workers,
            }
            samples.append(sample)
            print(
                f"terrain-hillclimb threads={threads} repeat={repeat}/{args.repeats} "
                f"chunkRenderTicks={chunk_ticks} elapsed={elapsed_millis:.3f}ms "
                f"wall={wall_seconds:.2f}s",
                flush=True,
            )

        tick_values = [float(sample["chunkRenderTicks"]) for sample in samples]
        elapsed_values = [float(sample["chunkRenderElapsedMillis"]) for sample in samples]
        summary = {
            "threads": threads,
            "samples": samples,
            "medianChunkRenderTicks": statistics.median(tick_values),
            "p95ChunkRenderTicks": percentile(tick_values, 0.95),
            "medianChunkRenderElapsedMillis": statistics.median(elapsed_values),
            "p95ChunkRenderElapsedMillis": percentile(elapsed_values, 0.95),
            "minChunkRenderTicks": min(tick_values),
            "maxChunkRenderTicks": max(tick_values),
        }
        results[threads] = summary
        return summary

    baseline = evaluate(baseline_threads)
    current_threads = baseline_threads
    path = [baseline_threads]
    decisions: list[dict[str, Any]] = []

    while True:
        current = evaluate(current_threads)
        neighbor_ids = [
            value for value in (current_threads - 1, current_threads + 1)
            if 1 <= value <= max_threads
        ]
        if not neighbor_ids:
            break

        neighbors = [evaluate(value) for value in neighbor_ids]
        best = min(
            neighbors,
            key=lambda result: (
                result["medianChunkRenderTicks"],
                result["medianChunkRenderElapsedMillis"],
                result["p95ChunkRenderTicks"],
                result["threads"],
            ),
        )
        current_median = float(current["medianChunkRenderTicks"])
        best_median = float(best["medianChunkRenderTicks"])
        improvement = (current_median - best_median) / current_median
        current_p95 = float(current["p95ChunkRenderTicks"])
        best_p95 = float(best["p95ChunkRenderTicks"])
        tail_ok = best_p95 <= current_p95 * (1.0 + args.tail_regression_limit)
        accepted = improvement >= args.min_improvement and tail_ok

        decisions.append({
            "fromThreads": current_threads,
            "candidateThreads": int(best["threads"]),
            "medianImprovement": improvement,
            "tailAccepted": tail_ok,
            "accepted": accepted,
        })
        if not accepted:
            break

        current_threads = int(best["threads"])
        path.append(current_threads)

    winner = evaluate(current_threads)
    baseline_median = float(baseline["medianChunkRenderTicks"])
    winner_median = float(winner["medianChunkRenderTicks"])
    total_improvement = (baseline_median - winner_median) / baseline_median

    report = {
        "schema": 2,
        "objective": "minimize repeated isolated waitForChunksRender readiness ticks",
        "scope": "Sodium client terrain chunk construction/upload readiness on Metal; Minecraft logical world generation is not scored",
        "benchmarkProject": str(bench.relative_to(root)),
        "machine": {
            "detectedLogicalProcessors": detected_cpus,
            "searchMaxThreads": max_threads,
        },
        "search": {
            "algorithm": "one-dimensional discrete hill climb",
            "repeatsPerCandidate": args.repeats,
            "minimumMedianImprovement": args.min_improvement,
            "tailRegressionLimit": args.tail_regression_limit,
            "sodiumDefaultThreads": baseline_threads,
            "path": path,
            "decisions": decisions,
        },
        "candidates": [results[key] for key in sorted(results)],
        "winner": {
            "threads": current_threads,
            "medianChunkRenderTicks": winner_median,
            "medianChunkRenderElapsedMillis": winner["medianChunkRenderElapsedMillis"],
            "p95ChunkRenderTicks": winner["p95ChunkRenderTicks"],
            "relativeMedianImprovementVsSodiumDefault": total_improvement,
            "recommendedSystemProperty": (
                f"-D{THREAD_PROPERTY}={current_threads}"
                if current_threads != baseline_threads else None
            ),
        },
    }
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2), flush=True)
    print(f"terrain hill-climb report: {output}", flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"terrain hill climb failed: {exc}", file=sys.stderr)
        raise
