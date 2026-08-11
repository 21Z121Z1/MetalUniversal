#!/usr/bin/env python3
"""Analyze same-runner ABBA measurements from hosted Metal performance experiments."""

from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
from statistics import median


def load(path: Path, expected_mode: str) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema") != 1:
        raise SystemExit(f"{path}: unsupported schema {data.get('schema')!r}")
    if data.get("benchmark") != "render-state-submission":
        raise SystemExit(f"{path}: unexpected benchmark {data.get('benchmark')!r}")
    if data.get("mode") != expected_mode:
        raise SystemExit(f"{path}: expected mode={expected_mode}, got {data.get('mode')!r}")
    samples = data.get("samples_ns")
    if not isinstance(samples, list) or len(samples) < 5 or any(not isinstance(v, int) or v <= 0 for v in samples):
        raise SystemExit(f"{path}: invalid samples_ns")
    telemetry = data.get("telemetry", {})
    if expected_mode == "packet":
        if telemetry.get("packet_calls", 0) <= 0 or telemetry.get("packet_entries", 0) <= 0:
            raise SystemExit(f"{path}: packet run did not prove packet execution")
        if telemetry.get("legacy_replays", 0) != 0:
            raise SystemExit(f"{path}: packet run replayed through legacy setters")
        if telemetry.get("collapsed_setter_downcalls", 0) <= 0:
            raise SystemExit(f"{path}: packet run collapsed no setter downcalls")
    else:
        if telemetry.get("packet_calls", 0) != 0 or telemetry.get("packet_entries", 0) != 0:
            raise SystemExit(f"{path}: legacy run unexpectedly executed packets")
    return data


def percentile_nearest_rank(values: list[int], q: float) -> int:
    ordered = sorted(values)
    index = max(0, math.ceil(q * len(ordered)) - 1)
    return ordered[min(index, len(ordered) - 1)]


def pct_change(candidate: float, baseline: float) -> float:
    return (candidate / baseline - 1.0) * 100.0


def summarize_run(data: dict) -> dict:
    samples = data["samples_ns"]
    med = float(median(samples))
    return {
        "median_ns": med,
        "p95_ns": percentile_nearest_rank(samples, 0.95),
        "median_ns_per_state_op": float(data["median_ns_per_state_op"]),
        "telemetry": data["telemetry"],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--legacy-a", type=Path, required=True)
    parser.add_argument("--packet-a", type=Path, required=True)
    parser.add_argument("--packet-b", type=Path, required=True)
    parser.add_argument("--legacy-b", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    legacy_a = load(args.legacy_a, "legacy")
    packet_a = load(args.packet_a, "packet")
    packet_b = load(args.packet_b, "packet")
    legacy_b = load(args.legacy_b, "legacy")

    # The two adjacent A/B blocks are the primary comparison. Pooling samples is
    # useful for descriptive p95 but the paired ratios are what control runner drift.
    la = summarize_run(legacy_a)
    pa = summarize_run(packet_a)
    pb = summarize_run(packet_b)
    lb = summarize_run(legacy_b)

    block_a_delta = pct_change(pa["median_ns"], la["median_ns"])
    block_b_delta = pct_change(pb["median_ns"], lb["median_ns"])
    paired_delta = float(median([block_a_delta, block_b_delta]))

    legacy_samples = legacy_a["samples_ns"] + legacy_b["samples_ns"]
    packet_samples = packet_a["samples_ns"] + packet_b["samples_ns"]
    pooled_legacy_median = float(median(legacy_samples))
    pooled_packet_median = float(median(packet_samples))
    pooled_delta = pct_change(pooled_packet_median, pooled_legacy_median)

    result = {
        "schema": 1,
        "experiment": "hosted-metal-render-state-packet-abba",
        "interpretation": "negative delta means packet mode is faster",
        "runs": {
            "legacy_a": la,
            "packet_a": pa,
            "packet_b": pb,
            "legacy_b": lb,
        },
        "paired": {
            "block_a_delta_percent": block_a_delta,
            "block_b_delta_percent": block_b_delta,
            "median_delta_percent": paired_delta,
            "median_improvement_percent": -paired_delta,
        },
        "pooled": {
            "legacy_median_ns": pooled_legacy_median,
            "packet_median_ns": pooled_packet_median,
            "legacy_p95_ns": percentile_nearest_rank(legacy_samples, 0.95),
            "packet_p95_ns": percentile_nearest_rank(packet_samples, 0.95),
            "delta_percent": pooled_delta,
            "improvement_percent": -pooled_delta,
        },
        "drift": {
            "legacy_b_vs_a_percent": pct_change(lb["median_ns"], la["median_ns"]),
            "packet_b_vs_a_percent": pct_change(pb["median_ns"], pa["median_ns"]),
        },
        "gate": {
            "performance_threshold_enforced": False,
            "reason": "first experiment establishes hosted-runner signal and variance before setting a regression threshold",
        },
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")

    print(json.dumps(result, indent=2))
    print(
        "HOSTED_METAL_PERF_ABBA "
        f"paired_delta={paired_delta:+.3f}% "
        f"paired_improvement={-paired_delta:+.3f}% "
        f"pooled_delta={pooled_delta:+.3f}%"
    )

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        lines = [
            "## Hosted Metal performance experiment",
            "",
            "`renderStatePacket` vs legacy per-setter FFM submission on the same `macos-26` runner.",
            "Negative delta means packet mode is faster.",
            "",
            "| Comparison | Legacy median | Packet median | Delta |",
            "|---|---:|---:|---:|",
            f"| A block | {la['median_ns'] / 1e6:.3f} ms | {pa['median_ns'] / 1e6:.3f} ms | {block_a_delta:+.2f}% |",
            f"| B block | {lb['median_ns'] / 1e6:.3f} ms | {pb['median_ns'] / 1e6:.3f} ms | {block_b_delta:+.2f}% |",
            f"| Pooled | {pooled_legacy_median / 1e6:.3f} ms | {pooled_packet_median / 1e6:.3f} ms | {pooled_delta:+.2f}% |",
            "",
            f"Paired median improvement: **{-paired_delta:+.2f}%**.",
            "",
            "This first lane is evidence-only: correctness/path assertions are mandatory, but no speed threshold is enforced until variance is characterized.",
        ]
        with open(summary_path, "a", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
