#!/usr/bin/env python3
"""Normalize and compare correctness-gated, interleaved render performance trials."""

from __future__ import annotations

import argparse
import json
import math
import statistics
from pathlib import Path
from typing import Any, Iterable

SCHEMA_VERSION = 1
MIN_PAIRED_BLOCKS = 4
MIN_DIRECTION_CONSISTENCY = 0.75
MANDATORY_METRICS = {"fps_median"}
ZERO_RUNTIME_CANDIDATE_METRICS = {"runtime_pipeline_compiles"}
GUARDRAILS = {
    "gpu_frame_time_ms_median": 0.02,
    "cpu_render_encode_time_ms_median": 0.02,
    "frame_time_ms_p99": 0.02,
    "peak_resident_memory_bytes": 0.03,
    "frame_time_stutter_count": 0.0,
    "frames_over_50_ms": 0.0,
    "frames_over_100_ms": 0.0,
}

METRICS = {
    "fps_median": {
        "aliases": {"fps", "fps_median", "frames_per_second", "framespersecond"},
        "direction": "higher",
        "unit": "FPS",
    },
    "fps_average": {
        "aliases": {"fps_average", "average_fps"},
        "direction": "higher",
        "unit": "FPS",
    },
    "fps_1_percent_low": {
        "aliases": {"fps_1_percent_low", "one_percent_low_fps", "1_percent_low"},
        "direction": "higher",
        "unit": "FPS",
    },
    "fps_0_1_percent_low": {
        "aliases": {"fps_0_1_percent_low", "point_one_percent_low_fps", "0_1_percent_low"},
        "direction": "higher",
        "unit": "FPS",
    },
    "frame_time_ms_p95": {
        "aliases": {"frame_time_ms_p95", "frame_interval_p95_milliseconds"},
        "direction": "lower",
        "unit": "ms",
    },
    "frame_time_ms_p99": {
        "aliases": {"frame_time_ms_p99", "frame_interval_p99_milliseconds"},
        "direction": "lower",
        "unit": "ms",
    },
    "frame_time_ms_p999": {
        "aliases": {"frame_time_ms_p999", "frame_interval_p999_milliseconds"},
        "direction": "lower",
        "unit": "ms",
    },
    "gpu_frame_time_ms_median": {
        "aliases": {"gpu_ms", "gpu_time_ms", "gpu_frame_ms", "gpu_frame_time_ms", "gpu_frame_time_ms_median"},
        "direction": "lower",
        "unit": "ms",
    },
    "gpu_frame_time_ms_p95": {
        "aliases": {"gpu_frame_time_ms_p95", "gpu_p95_milliseconds"},
        "direction": "lower",
        "unit": "ms",
    },
    "gpu_frame_time_ms_p99": {
        "aliases": {"gpu_frame_time_ms_p99", "gpu_p99_milliseconds"},
        "direction": "lower",
        "unit": "ms",
    },
    "cpu_render_encode_time_ms_median": {
        "aliases": {"cpu_ms", "cpu_time_ms", "cpu_render_ms", "cpu_encode_ms",
                    "cpu_render_encode_time_ms", "cpu_render_encode_time_ms_median"},
        "direction": "lower",
        "unit": "ms",
    },
    "native_encoder_count_per_frame_median": {
        "aliases": {"encoder_count", "encoders_per_frame", "native_encoder_count",
                    "native_encoder_count_per_frame", "native_encoder_count_per_frame_median"},
        "direction": "lower",
        "unit": "encoders/frame",
    },
    "render_pass_store_load_bytes_estimate_median": {
        "aliases": {"store_load_bytes", "attachment_bandwidth_bytes", "attachment_store_load_bytes",
                    "render_pass_store_load_bytes_estimate_median"},
        "direction": "lower",
        "unit": "bytes/frame",
    },
    "resident_render_resource_bytes": {
        "aliases": {"resident_resource_bytes", "resident_render_resource_bytes"},
        "direction": "lower",
        "unit": "bytes",
    },
    "peak_resident_memory_bytes": {
        "aliases": {"peak_memory_bytes", "peak_resident_memory_bytes"},
        "direction": "lower",
        "unit": "bytes",
    },
    "frame_time_stutter_count": {
        "aliases": {"stutter_count", "frame_time_stutter_count"},
        "direction": "lower",
        "unit": "events",
    },
    "frames_over_33_3_ms": {
        "aliases": {"frames_over_33_3_ms", "frames_over33_3_milliseconds"},
        "direction": "lower",
        "unit": "frames",
    },
    "frames_over_50_ms": {
        "aliases": {"frames_over_50_ms", "frames_over50_milliseconds"},
        "direction": "lower",
        "unit": "frames",
    },
    "frames_over_100_ms": {
        "aliases": {"frames_over_100_ms", "frames_over100_milliseconds"},
        "direction": "lower",
        "unit": "frames",
    },
    "java_to_native_ffm_calls_per_frame": {
        "aliases": {"java_to_native_ffm_calls_per_frame"},
        "direction": "lower",
        "unit": "calls/frame",
    },
    "native_setter_operations_per_frame": {
        "aliases": {"native_setter_operations_per_frame"},
        "direction": "lower",
        "unit": "operations/frame",
    },
    "render_packet_calls_per_frame": {
        "aliases": {"render_packet_calls_per_frame"},
        "direction": "lower",
        "unit": "calls/frame",
    },
    "render_packet_replays": {
        "aliases": {"render_packet_replays"},
        "direction": "lower",
        "unit": "replays",
    },
    "compute_packet_calls_per_frame": {
        "aliases": {"compute_packet_calls_per_frame"},
        "direction": "lower",
        "unit": "calls/frame",
    },
    "compute_packet_replays": {
        "aliases": {"compute_packet_replays"},
        "direction": "lower",
        "unit": "replays",
    },
    "argument_table_updates_per_frame": {
        "aliases": {"argument_table_updates_per_frame"},
        "direction": "lower",
        "unit": "updates/frame",
    },
    "terrain_icb_accepted": {
        "aliases": {"terrain_icb_accepted"},
        "direction": "higher",
        "unit": "batches",
    },
    "runtime_pipeline_compiles": {
        "aliases": {"runtime_pipeline_compiles"},
        "direction": "lower",
        "unit": "compiles",
    },
}

ALIAS_TO_METRIC = {
    alias: metric
    for metric, spec in METRICS.items()
    for alias in spec["aliases"]
}


def normalized_key(value: str) -> str:
    return "".join(ch.lower() if ch.isalnum() else "_" for ch in value).strip("_")


def finite_number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    value = float(value)
    return value if math.isfinite(value) else None


def collect_json_metrics(node: Any, out: dict[str, list[float]]) -> None:
    if isinstance(node, dict):
        for raw_key, value in node.items():
            key = normalized_key(str(raw_key))
            metric = ALIAS_TO_METRIC.get(key)
            if metric:
                direct = finite_number(value)
                if direct is not None:
                    out[metric].append(direct)
                elif isinstance(value, dict):
                    for summary_key in ("median", "value", "mean"):
                        candidate = finite_number(value.get(summary_key))
                        if candidate is not None:
                            out[metric].append(candidate)
                            break
            collect_json_metrics(value, out)
    elif isinstance(node, list):
        for value in node:
            collect_json_metrics(value, out)


def iter_json_files(root: Path) -> Iterable[Path]:
    for path in sorted(root.rglob("*.json")):
        if path.name in {"metrics.json", "comparison.json", "decision.json"}:
            continue
        if any(part in {".git", "generated"} for part in path.parts):
            continue
        yield path


def normalize_trial(trial_dir: Path) -> dict[str, Any]:
    values = {metric: [] for metric in METRICS}
    parse_errors: list[dict[str, str]] = []
    source_files: list[str] = []
    for path in iter_json_files(trial_dir):
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            parse_errors.append({"file": str(path), "error": str(exc)})
            continue
        before = sum(len(v) for v in values.values())
        collect_json_metrics(payload, values)
        after = sum(len(v) for v in values.values())
        if after > before:
            source_files.append(str(path.relative_to(trial_dir)))

    status_path = trial_dir / "exit-status.txt"
    try:
        exit_status = int(status_path.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        exit_status = None
    normalized: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "trial_dir": str(trial_dir),
        "exit_status": exit_status,
        "complete": exit_status == 0,
        "sources": source_files,
        "parse_errors": parse_errors,
        "metrics": {},
    }
    for metric, spec in METRICS.items():
        samples = values[metric]
        if samples:
            normalized["metrics"][metric] = {
                "available": True,
                "median": statistics.median(samples),
                "sample_count": len(samples),
                "minimum": min(samples),
                "maximum": max(samples),
                "unit": spec["unit"],
                "direction": spec["direction"],
            }
        else:
            normalized["metrics"][metric] = {
                "available": False,
                "reason": "no structured source emitted a recognized metric key",
                "sample_count": 0,
                "unit": spec["unit"],
                "direction": spec["direction"],
            }
    (trial_dir / "metrics.json").write_text(
        json.dumps(normalized, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return normalized


def read_gate(root: Path) -> tuple[bool, dict[str, Any]]:
    path = root / "correctness" / "gate.json"
    if not path.is_file():
        return False, {"status": "missing", "reason": f"{path} does not exist"}
    try:
        gate = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return False, {"status": "malformed", "reason": str(exc)}
    return gate.get("status") == "pass", gate


def load_trial(path: Path) -> dict[str, Any]:
    metrics_path = path / "metrics.json"
    if not metrics_path.is_file():
        return normalize_trial(path)
    return json.loads(metrics_path.read_text(encoding="utf-8"))


def paired_improvement(before: float, after: float, direction: str) -> tuple[float, float | None]:
    raw = after - before
    if before == 0:
        return raw, None
    if direction == "higher":
        improvement = raw / abs(before) * 100.0
    else:
        improvement = -raw / abs(before) * 100.0
    return raw, improvement


def analyze(root: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    correctness_passed, gate = read_gate(root)
    blocks = sorted((root / "trials").glob("block-*"))
    comparison: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "root": str(root),
        "correctness_gate": gate,
        "block_count": len(blocks),
        "metrics": {},
    }

    any_stable_positive = False
    missing_metrics: list[str] = []
    for metric, spec in METRICS.items():
        pairs: list[dict[str, float | None]] = []
        for block in blocks:
            baseline_trial = load_trial(block / "baseline")
            candidate_trial = load_trial(block / "candidate")
            if not baseline_trial.get("complete") or not candidate_trial.get("complete"):
                continue
            baseline = baseline_trial["metrics"].get(metric, {})
            candidate = candidate_trial["metrics"].get(metric, {})
            if not baseline.get("available") or not candidate.get("available"):
                continue
            before = float(baseline["median"])
            after = float(candidate["median"])
            raw, pct = paired_improvement(before, after, spec["direction"])
            pairs.append({"before": before, "after": after, "raw_delta": raw,
                          "improvement_percent": pct})

        if not pairs:
            comparison["metrics"][metric] = {
                "available": False,
                "reason": "no complete block contained both normalized baseline and candidate values",
                "direction": spec["direction"],
                "unit": spec["unit"],
                "paired_blocks": 0,
            }
            missing_metrics.append(metric)
            continue

        before_median = statistics.median(float(pair["before"]) for pair in pairs)
        after_median = statistics.median(float(pair["after"]) for pair in pairs)
        raw, pct = paired_improvement(before_median, after_median, spec["direction"])
        positive_count = sum(
            1 for pair in pairs
            if pair["improvement_percent"] is not None and float(pair["improvement_percent"]) > 0
        )
        negative_count = sum(
            1 for pair in pairs
            if pair["improvement_percent"] is not None and float(pair["improvement_percent"]) < 0
        )
        consistency = positive_count / len(pairs)
        stable_positive = (
            len(pairs) >= MIN_PAIRED_BLOCKS
            and consistency >= MIN_DIRECTION_CONSISTENCY
            and pct is not None
            and pct > 0
        )
        any_stable_positive = any_stable_positive or stable_positive
        comparison["metrics"][metric] = {
            "available": True,
            "before": before_median,
            "after": after_median,
            "raw_delta_after_minus_before": raw,
            "improvement_percent": pct,
            "paired_blocks": len(pairs),
            "positive_blocks": positive_count,
            "negative_blocks": negative_count,
            "direction_consistency": consistency,
            "stable_positive": stable_positive,
            "direction": spec["direction"],
            "unit": spec["unit"],
            "pairs": pairs,
        }

    guardrail_regressions: list[dict[str, Any]] = []
    for metric, limit in GUARDRAILS.items():
        result = comparison["metrics"].get(metric, {})
        if not result.get("available"):
            continue
        pct = result.get("improvement_percent")
        if pct is not None and float(pct) < -(limit * 100.0):
            guardrail_regressions.append({
                "metric": metric,
                "observed_regression_percent": -float(pct),
                "allowed_regression_percent": limit * 100.0,
            })

    mandatory_missing = sorted(MANDATORY_METRICS.intersection(missing_metrics))
    zero_runtime_failures: list[dict[str, Any]] = []
    for metric in ZERO_RUNTIME_CANDIDATE_METRICS:
        result = comparison["metrics"].get(metric, {})
        if not result.get("available") or result.get("paired_blocks") != len(blocks):
            zero_runtime_failures.append({
                "metric": metric,
                "reason": "missing from one or more complete candidate blocks",
            })
            continue
        nonzero = [
            {"block": index + 1, "value": pair["after"]}
            for index, pair in enumerate(result.get("pairs", []))
            if pair.get("after") != 0
        ]
        if nonzero:
            zero_runtime_failures.append({
                "metric": metric,
                "reason": "candidate compiled pipelines after warmup",
                "nonzero_blocks": nonzero,
            })
    if not correctness_passed:
        state = "rejected-correctness-gate"
        reason = "correctness gate did not pass"
    elif mandatory_missing:
        state = "inconclusive-noise"
        reason = "mandatory structured performance metrics are missing"
    elif len(blocks) < MIN_PAIRED_BLOCKS:
        state = "inconclusive-noise"
        reason = f"fewer than {MIN_PAIRED_BLOCKS} paired blocks"
    elif zero_runtime_failures:
        state = "rejected-regression"
        reason = "candidate runtime pipeline compilation gate failed"
    elif guardrail_regressions:
        state = "rejected-regression"
        reason = "one or more guardrail metrics regressed beyond the allowed limit"
    elif any_stable_positive:
        state = "accepted-candidate"
        reason = "at least one metric improved consistently and all correctness/guardrail gates passed"
    else:
        state = "inconclusive-noise"
        reason = "no metric met the paired direction-consistency rule"

    decision = {
        "schema_version": SCHEMA_VERSION,
        "state": state,
        "reason": reason,
        "correctness_passed": correctness_passed,
        "any_stable_positive_metric": any_stable_positive,
        "missing_mandatory_metrics": mandatory_missing,
        "guardrail_regressions": guardrail_regressions,
        "zero_runtime_failures": zero_runtime_failures,
        "minimum_paired_blocks": MIN_PAIRED_BLOCKS,
        "minimum_direction_consistency": MIN_DIRECTION_CONSISTENCY,
        "acceptance_note": (
            "This automated decision is a gate, not proof of root cause. "
            "Source reports and visual/attachment evidence remain authoritative."
        ),
    }
    (root / "comparison.json").write_text(
        json.dumps(comparison, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (root / "decision.json").write_text(
        json.dumps(decision, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    write_markdown(root / "comparison.md", comparison, decision)
    return comparison, decision


def format_value(value: Any, unit: str) -> str:
    if value is None:
        return "undefined"
    return f"{value:.6g} {unit}".strip()


def write_markdown(path: Path, comparison: dict[str, Any], decision: dict[str, Any]) -> None:
    lines = [
        "# Unified render evaluation",
        "",
        f"Decision: `{decision['state']}` — {decision['reason']}.",
        "",
        "| Metric | Before | After | Raw delta | Efficiency improvement | Paired blocks | Positive blocks |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for metric, spec in METRICS.items():
        result = comparison["metrics"][metric]
        if not result.get("available"):
            lines.append(f"| {metric} | unavailable | unavailable | — | — | 0 | 0 |")
            continue
        pct = result.get("improvement_percent")
        pct_text = "undefined" if pct is None else f"{pct:+.3f}%"
        lines.append(
            f"| {metric} | {format_value(result['before'], spec['unit'])} | "
            f"{format_value(result['after'], spec['unit'])} | "
            f"{format_value(result['raw_delta_after_minus_before'], spec['unit'])} | "
            f"{pct_text} | {result['paired_blocks']} | {result['positive_blocks']} |"
        )
    lines.extend([
        "",
        f"The decision requires at least {MIN_PAIRED_BLOCKS} paired blocks, "
        f"at least {MIN_DIRECTION_CONSISTENCY:.0%} positive blocks for one metric, "
        "a passing correctness gate, mandatory structured FPS, and no guardrail regression.",
        "",
    ])
    path.write_text("\n".join(lines), encoding="utf-8")


def self_test() -> None:
    import tempfile
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        (root / "correctness").mkdir()
        (root / "correctness" / "gate.json").write_text(
            json.dumps({"status": "pass"}), encoding="utf-8"
        )
        for block_number, baseline, candidate in (
            (1, 100.0, 110.0),
            (2, 101.0, 111.0),
            (3, 99.0, 109.0),
            (4, 100.5, 110.5),
        ):
            for profile, fps in (("baseline", baseline), ("candidate", candidate)):
                trial = root / "trials" / f"block-{block_number:03d}" / profile
                trial.mkdir(parents=True)
                (trial / "exit-status.txt").write_text("0\n", encoding="utf-8")
                (trial / "source.json").write_text(
                    json.dumps({
                        "fps": {"median": fps},
                        "gpu_frame_time_ms": 10.0,
                        "runtime_pipeline_compiles": 0,
                    }),
                    encoding="utf-8",
                )
        _, decision = analyze(root)
        assert decision["state"] == "accepted-candidate", decision
    print("self-test: PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path)
    parser.add_argument("--normalize-trial", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.normalize_trial:
        normalize_trial(args.normalize_trial)
        return 0
    if args.root:
        _, decision = analyze(args.root)
        print(json.dumps(decision, indent=2))
        return 0 if decision["state"] == "accepted-candidate" else 2
    parser.error("choose --root, --normalize-trial, or --self-test")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
