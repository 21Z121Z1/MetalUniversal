#!/usr/bin/env python3
"""Greedy hosted-Metal3 proxy hill climb over shared renderer toggles.

The runner keeps every candidate on one macOS job and uses interleaved paired
trials. Results are screening evidence only: the Apple Paravirtual device is
not authoritative for Metal 4 or physical-Mac promotion.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
from pathlib import Path
import shutil
import statistics
import subprocess
import sys
import time
from typing import Any

KNOBS = (
    "deferredStore",
    "deferredColorStore",
    "blitBatch",
    "encoderStateShadow",
)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--repo-root", type=Path, default=Path.cwd())
    p.add_argument("--bench-project", type=Path, default=Path(".github/ci/gpu-bench"))
    p.add_argument("--metallum-jar", type=Path, required=True)
    p.add_argument("--warmup-ticks", type=int, default=20)
    p.add_argument("--sample-ticks", type=int, default=60)
    p.add_argument("--min-improvement", type=float, default=0.01)
    p.add_argument("--throughput-regression-limit", type=float, default=0.05)
    p.add_argument("--gpu-p50-regression-limit", type=float, default=0.05)
    p.add_argument("--output", type=Path, default=Path("build/gpu-hillclimb/report.json"))
    return p.parse_args()


def median(values: list[float]) -> float:
    return statistics.median(values)


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
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


def summarize_gpu_trace(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """Describe main-queue occupancy without perturbing the measured render path."""
    ordered = sorted(
        rows,
        key=lambda row: (float(row["gpuStartTimeSeconds"]), int(row["submitIndex"])),
    )
    services = [float(row["gpuMillis"]) for row in ordered]
    starts = [float(row["gpuStartTimeSeconds"]) for row in ordered]
    ends = [float(row["gpuEndTimeSeconds"]) for row in ordered]

    # Merge intervals even though one Metal command queue should serialize its
    # command buffers. Treating accidental timestamp overlap defensively avoids
    # reporting an impossible >100% queue occupancy on a virtualized device.
    merged: list[list[float]] = []
    for start, end in zip(starts, ends):
        if not merged or start > merged[-1][1]:
            merged.append([start, end])
        else:
            merged[-1][1] = max(merged[-1][1], end)

    busy_seconds = sum(end - start for start, end in merged)
    span_seconds = max(0.0, merged[-1][1] - merged[0][0]) if merged else 0.0
    idle_gaps_ms: list[float] = []
    for index in range(1, len(merged)):
        idle_gaps_ms.append(max(0.0, (merged[index][0] - merged[index - 1][1]) * 1000.0))
    total_idle_ms = sum(idle_gaps_ms)
    busy_ratio = busy_seconds / span_seconds if span_seconds > 0.0 else 0.0

    submit_indices = [int(row["submitIndex"]) for row in ordered]
    return {
        "sampleCount": len(ordered),
        "submitIndexMin": min(submit_indices) if submit_indices else None,
        "submitIndexMax": max(submit_indices) if submit_indices else None,
        "observedSpanMillis": span_seconds * 1000.0,
        "busyMillis": busy_seconds * 1000.0,
        "totalIdleMillis": total_idle_ms,
        "mainQueueBusyRatio": min(1.0, max(0.0, busy_ratio)),
        "gpuServiceP50Millis": percentile(services, 0.50),
        "gpuServiceP95Millis": percentile(services, 0.95),
        "gpuServiceP99Millis": percentile(services, 0.99),
        "idleGapCount": len(idle_gaps_ms),
        "idleGapP50Millis": percentile(idle_gaps_ms, 0.50),
        "idleGapP95Millis": percentile(idle_gaps_ms, 0.95),
        "idleGapMaxMillis": max(idle_gaps_ms) if idle_gaps_ms else 0.0,
    }


def classify_queue(profile: dict[str, Any]) -> tuple[str, str, str]:
    busy_ratio = float(profile["mainQueueBusyRatio"])
    gap_p95 = float(profile["idleGapP95Millis"])
    if busy_ratio >= 0.90:
        return (
            "main-queue-saturated-proxy",
            "medium",
            "The hosted Metal main queue stays busy for at least 90% of the observed GPU window. "
            "Prefer GPU-work reduction or renderer architecture ablations before adding more CPU scheduling complexity.",
        )
    if busy_ratio <= 0.70:
        return (
            "main-queue-starved-or-submission-limited-proxy",
            "medium" if gap_p95 >= 0.10 else "low",
            "The hosted Metal main queue is idle for a material fraction of the observed window. "
            "Instrument CPU render submission, terrain build/upload readiness and synchronization before shader micro-tuning.",
        )
    return (
        "mixed-main-queue-pressure-proxy",
        "low",
        "The hosted queue has neither persistent saturation nor strong starvation. "
        "Use the per-frame spikes and paired ablations to separate GPU work from CPU/submission pressure.",
    )


def main() -> int:
    args = parse_args()
    if args.warmup_ticks <= 0 or args.sample_ticks <= 0:
        raise SystemExit("warmup/sample ticks must be positive")
    root = args.repo_root.resolve()
    bench = args.bench_project if args.bench_project.is_absolute() else root / args.bench_project
    bench = bench.resolve()
    jar = args.metallum_jar.resolve()
    if not jar.is_file():
        raise SystemExit(f"MetalUniversal JAR not found: {jar}")
    if not (bench / "build.gradle").is_file():
        raise SystemExit(f"GPU benchmark project not found: {bench}")

    output = args.output if args.output.is_absolute() else root / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    bottleneck_output = output.parent / "bottleneck-report.json"
    trials_root = output.parent / "trials"
    shutil.rmtree(trials_root, ignore_errors=True)
    trials_root.mkdir(parents=True, exist_ok=True)
    evidence = bench / "build" / "evidence"
    runtime_json = evidence / "gpu-benchmark.json"
    runtime_trace = evidence / "gpu-frame-trace.jsonl"
    latest_log = bench / "build" / "run" / "clientGameTest" / "logs" / "latest.log"

    trial_index = 0

    def run_trial(config: dict[str, bool], label: str) -> dict[str, Any]:
        nonlocal trial_index
        trial_index += 1
        trial_dir = trials_root / f"{trial_index:03d}-{label}"
        trial_dir.mkdir(parents=True, exist_ok=True)
        shutil.rmtree(evidence, ignore_errors=True)

        command = [
            str(root / "gradlew"), "--no-daemon", "--build-cache", "--stacktrace",
            "-p", str(bench), f"-PmetallumJar={jar}",
            f"-PgpuWarmupTicks={args.warmup_ticks}",
            f"-PgpuSampleTicks={args.sample_ticks}",
        ]
        for knob in KNOBS:
            command.append(f"-P{knob}={str(config[knob]).lower()}")
        command.append("runProductionGpuBenchmark")

        (trial_dir / "command.json").write_text(json.dumps(command, indent=2) + "\n", encoding="utf-8")
        started = time.perf_counter()
        with (trial_dir / "runner.log").open("w", encoding="utf-8") as log:
            completed = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
        wall = time.perf_counter() - started
        if completed.returncode != 0:
            raise RuntimeError(f"GPU benchmark failed for {label}; see {trial_dir / 'runner.log'}")
        if not runtime_json.is_file():
            raise RuntimeError(f"missing GPU benchmark evidence for {label}")
        if not runtime_trace.is_file():
            raise RuntimeError(f"missing raw GPU frame trace for {label}")
        data = json.loads(runtime_json.read_text(encoding="utf-8"))
        if str(data.get("backend", "")).lower() != "metal":
            raise RuntimeError(f"GPU benchmark did not use Metal: {data}")
        if data.get("authority") != "hosted-metal3-proxy-screening":
            raise RuntimeError(f"unexpected benchmark authority: {data}")
        if int(data.get("gpuSampleCount", 0)) < 30:
            raise RuntimeError(f"insufficient GPU samples: {data}")
        observed = data.get("options", {})
        for knob in KNOBS:
            if bool(observed.get(knob)) != bool(config[knob]):
                raise RuntimeError(f"{label}: requested {knob}={config[knob]}, observed {observed.get(knob)}")

        trace_rows: list[dict[str, Any]] = []
        for line_number, line in enumerate(runtime_trace.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise RuntimeError(f"{label}: invalid frame trace JSON on line {line_number}") from exc
            if row.get("authority") != "hosted-metal3-proxy-screening":
                raise RuntimeError(f"{label}: unexpected frame trace authority on line {line_number}: {row}")
            if "submitIndex" not in row or float(row.get("gpuMillis", 0.0)) <= 0.0:
                raise RuntimeError(f"{label}: invalid frame trace row on line {line_number}: {row}")
            trace_rows.append(row)
        if len(trace_rows) != int(data["gpuSampleCount"]):
            raise RuntimeError(
                f"{label}: frame trace/sample mismatch: trace={len(trace_rows)} metrics={data['gpuSampleCount']}"
            )

        queue_profile = summarize_gpu_trace(trace_rows)
        data["trialLabel"] = label
        data["wallSeconds"] = round(wall, 6)
        data["gpuFrameTraceRows"] = len(trace_rows)
        data["gpuQueueProfile"] = queue_profile
        (trial_dir / "metrics.json").write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        shutil.copy2(runtime_trace, trial_dir / "gpu-frame-trace.jsonl")
        if latest_log.is_file():
            shutil.copy2(latest_log, trial_dir / "latest.log")
        print(
            f"gpu-hillclimb {label}: p95={data['gpuP95Millis']:.4f}ms "
            f"p50={data['gpuP50Millis']:.4f}ms completed={data['completedGpuFramesPerSecond']:.1f}/s "
            f"trace={len(trace_rows)} frames busy={queue_profile['mainQueueBusyRatio']:.3f} "
            f"gap-p95={queue_profile['idleGapP95Millis']:.4f}ms",
            flush=True,
        )
        return data

    def paired_compare(baseline: list[dict[str, Any]], candidate: list[dict[str, Any]]) -> dict[str, Any]:
        if len(baseline) != len(candidate) or not baseline:
            raise ValueError("paired compare requires equal non-empty arms")
        improvements = []
        fps_ratios = []
        p50_ratios = []
        for b, c in zip(baseline, candidate):
            bp95 = float(b["gpuP95Millis"])
            cp95 = float(c["gpuP95Millis"])
            improvements.append((bp95 - cp95) / bp95)
            fps_ratios.append(float(c["completedGpuFramesPerSecond"]) / float(b["completedGpuFramesPerSecond"]))
            p50_ratios.append(float(c["gpuP50Millis"]) / float(b["gpuP50Millis"]))
        return {
            "pairedGpuP95Improvements": improvements,
            "medianGpuP95Improvement": median(improvements),
            "positiveFraction": sum(1 for x in improvements if x > 0.0) / len(improvements),
            "medianThroughputRatio": median(fps_ratios),
            "medianGpuP50Ratio": median(p50_ratios),
        }

    original = {knob: True for knob in KNOBS}
    champion = copy.deepcopy(original)

    # A/A calibration estimates within-job noise before any candidate can move uphill.
    aa1 = run_trial(champion, "aa-1")
    aa2 = run_trial(champion, "aa-2")
    aa_p95 = [float(aa1["gpuP95Millis"]), float(aa2["gpuP95Millis"])]
    aa_mid = median(aa_p95)
    aa_relative_delta = abs(aa_p95[0] - aa_p95[1]) / aa_mid if aa_mid > 0 else math.inf
    move_threshold = max(args.min_improvement, aa_relative_delta * 2.0)

    decisions: list[dict[str, Any]] = []
    for knob in KNOBS:
        candidate = copy.deepcopy(champion)
        candidate[knob] = not candidate[knob]

        # Two close interleaved blocks for screening: B->C then C->B.
        b1 = run_trial(champion, f"{knob}-block1-baseline")
        c1 = run_trial(candidate, f"{knob}-block1-candidate")
        c2 = run_trial(candidate, f"{knob}-block2-candidate")
        b2 = run_trial(champion, f"{knob}-block2-baseline")
        comparison = paired_compare([b1, b2], [c1, c2])
        throughput_ok = comparison["medianThroughputRatio"] >= 1.0 - args.throughput_regression_limit
        p50_ok = comparison["medianGpuP50Ratio"] <= 1.0 + args.gpu_p50_regression_limit
        accepted = (
            comparison["positiveFraction"] == 1.0
            and comparison["medianGpuP95Improvement"] >= move_threshold
            and throughput_ok and p50_ok
        )
        decisions.append({
            "knob": knob,
            "from": champion[knob],
            "to": candidate[knob],
            "accepted": accepted,
            "threshold": move_threshold,
            "throughputGuardPass": throughput_ok,
            "gpuP50GuardPass": p50_ok,
            **comparison,
        })
        if accepted:
            champion = candidate

    confirmation: dict[str, Any] | None = None
    if champion != original:
        baseline_trials: list[dict[str, Any]] = []
        candidate_trials: list[dict[str, Any]] = []
        # Four paired ABBA blocks. Pairing is by block, not by pooled frames.
        orders = (("baseline", "candidate"), ("candidate", "baseline"),
                  ("baseline", "candidate"), ("candidate", "baseline"))
        for block, order in enumerate(orders, 1):
            block_results: dict[str, dict[str, Any]] = {}
            for arm in order:
                config = original if arm == "baseline" else champion
                block_results[arm] = run_trial(config, f"confirm-block{block}-{arm}")
            baseline_trials.append(block_results["baseline"])
            candidate_trials.append(block_results["candidate"])
        comparison = paired_compare(baseline_trials, candidate_trials)
        throughput_ok = comparison["medianThroughputRatio"] >= 1.0 - args.throughput_regression_limit
        p50_ok = comparison["medianGpuP50Ratio"] <= 1.0 + args.gpu_p50_regression_limit
        confirmation = {
            **comparison,
            "requiredPositiveFraction": 0.75,
            "threshold": move_threshold,
            "throughputGuardPass": throughput_ok,
            "gpuP50GuardPass": p50_ok,
            "proxyConfirmed": (
                comparison["positiveFraction"] >= 0.75
                and comparison["medianGpuP95Improvement"] >= move_threshold
                and throughput_ok and p50_ok
            ),
        }

    state = "no-change"
    if champion != original:
        state = "proxy-confirmed-candidate" if confirmation and confirmation["proxyConfirmed"] else "inconclusive-screening-candidate"

    # One clean final trace gives the next agent a stable bottleneck target even
    # when the last hill-climb arm was a rejected candidate. It uses the same
    # shipping topology and timing mode as the formal trials.
    champion_profile = run_trial(champion, "profile-champion")
    queue_profile = champion_profile["gpuQueueProfile"]
    classification, confidence, recommendation = classify_queue(queue_profile)
    bottleneck_report = {
        "schema": 1,
        "authority": "hosted-metal3-proxy-screening",
        "sourceSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "classification": classification,
        "confidence": confidence,
        "champion": champion,
        "gpuP50Millis": champion_profile["gpuP50Millis"],
        "gpuP95Millis": champion_profile["gpuP95Millis"],
        "completedGpuFramesPerSecond": champion_profile["completedGpuFramesPerSecond"],
        "mainQueue": queue_profile,
        "recommendedDirection": recommendation,
        "limitations": [
            "Main-command-buffer timestamps expose queue occupancy, not per-render-pass GPU counters.",
            "Hosted Apple Paravirtual Metal 3 is a comparative proxy, not physical-Mac Metal 4 authority.",
            "A low busy ratio narrows the search to submission/work-generation/synchronization but does not by itself identify the CPU producer.",
        ],
    }
    bottleneck_output.write_text(json.dumps(bottleneck_report, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    report = {
        "schema": 1,
        "authority": "hosted-metal3-proxy-screening",
        "state": state,
        "sourceSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "objective": "minimize completed main-command-buffer GPU p95 without throughput or GPU p50 regression",
        "aANoise": {
            "gpuP95Millis": aa_p95,
            "relativeDelta": aa_relative_delta,
            "moveThreshold": move_threshold,
            "queueProfiles": [aa1["gpuQueueProfile"], aa2["gpuQueueProfile"]],
        },
        "original": original,
        "champion": champion,
        "decisions": decisions,
        "confirmation": confirmation,
        "championProfile": champion_profile,
        "bottleneckReport": str(bottleneck_output.relative_to(output.parent)),
        "trialCount": trial_index,
        "limitations": [
            "Apple Paravirtual Metal 3 proxy, not physical-Mac Metal 4 authority",
            "performance screening only; full render-contract correctness remains a promotion gate",
            "each Client GameTest creates an isolated world, so A/A calibration is part of the acceptance threshold",
        ],
    }
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, sort_keys=True))
    print("bottleneck-report:")
    print(json.dumps(bottleneck_report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"gpu hill climb failed: {exc}", file=sys.stderr)
        raise
