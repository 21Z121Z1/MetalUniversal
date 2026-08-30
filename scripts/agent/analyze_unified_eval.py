#!/usr/bin/env python3
"""Normalize and compare correctness-gated, interleaved render performance trials."""

from __future__ import annotations

import argparse
import json
import math
import statistics
from pathlib import Path
from typing import Any, Iterable

try:
    # The strict per-trial normalizer lives next to this analyzer.  Keeping
    # this import optional preserves a useful diagnostic fallback when the
    # script is copied in isolation; load_trial still marks that fallback
    # incomplete below.
    from normalize_unified_trial import normalize as normalize_strict_trial
except ImportError:  # pragma: no cover - exercised only outside the repo
    normalize_strict_trial = None

# The comparison/decision payload now carries environment identity and a
# complete-paired-block gate in addition to the original metric table.
SCHEMA_VERSION = 2
MIN_PAIRED_BLOCKS = 4
MIN_DIRECTION_CONSISTENCY = 0.75
MANDATORY_METRICS = {
    "fps_median",
    "frame_time_ms_p95",
    "gpu_frame_time_ms_p95",
    "cpu_render_encode_time_ms_p95",
}
GUARDRAILS = {
    "gpu_frame_time_ms_median": 0.02,
    "gpu_frame_time_ms_p99": 0.02,
    "cpu_render_encode_time_ms_median": 0.02,
    "cpu_render_encode_time_ms_p99": 0.02,
    "peak_resident_memory_bytes": 0.03,
    "frame_time_stutter_count": 0.0,
}

METRICS = {
    "fps_median": {
        "aliases": {"fps", "fps_median", "frames_per_second", "framespersecond"},
        "direction": "higher",
        "unit": "FPS",
    },
    "frame_time_ms_p95": {
        "aliases": {"frame_time_ms_p95", "frame_interval_p95_ms", "frame_interval_p95_milliseconds"},
        "direction": "lower",
        "unit": "ms",
    },
    "frame_time_ms_p99": {
        "aliases": {"frame_time_ms_p99", "frame_interval_p99_ms", "frame_interval_p99_milliseconds"},
        "direction": "lower",
        "unit": "ms",
    },
    "gpu_frame_time_ms_median": {
        "aliases": {"gpu_ms", "gpu_time_ms", "gpu_frame_ms", "gpu_frame_time_ms", "gpu_frame_time_ms_median"},
        "direction": "lower",
        "unit": "ms",
    },
    "gpu_frame_time_ms_p95": {
        "aliases": {"gpu_frame_time_ms_p95", "gpu_p95_ms", "gpu_p95_milliseconds"},
        "direction": "lower",
        "unit": "ms",
    },
    "gpu_frame_time_ms_p99": {
        "aliases": {"gpu_frame_time_ms_p99", "gpu_p99_ms", "gpu_p99_milliseconds"},
        "direction": "lower",
        "unit": "ms",
    },
    "cpu_render_encode_time_ms_median": {
        "aliases": {"cpu_ms", "cpu_time_ms", "cpu_render_ms", "cpu_encode_ms",
                    "cpu_render_encode_time_ms", "cpu_render_encode_time_ms_median"},
        "direction": "lower",
        "unit": "ms",
    },
    "cpu_render_encode_time_ms_p95": {
        "aliases": {"cpu_render_encode_time_ms_p95", "cpu_p95_ms", "cpu_p95_milliseconds"},
        "direction": "lower",
        "unit": "ms",
    },
    "cpu_render_encode_time_ms_p99": {
        "aliases": {"cpu_render_encode_time_ms_p99", "cpu_p99_ms", "cpu_p99_milliseconds"},
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
        # This compatibility path is deliberately fail-closed.  The old
        # recursive extractor may discover useful diagnostics, but it cannot
        # prove that frame/GPU/CPU p95 values belong to the same measured
        # frame window.  Only normalize_unified_trial.py can do that.
        "complete": False,
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


def read_run_manifest(root: Path) -> tuple[bool, dict[str, Any], list[str]]:
    """Validate the environment identity required for a performance verdict."""
    path = root / "run-manifest.json"
    if not path.is_file():
        return False, {}, [f"{path} does not exist"]
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return False, {}, [f"run manifest is unreadable: {exc}"]
    if not isinstance(manifest, dict):
        return False, {}, ["run manifest root must be an object"]

    errors: list[str] = []
    environment = manifest.get("environment")
    if not isinstance(environment, dict):
        errors.append("run manifest environment object is missing")
        environment = {}
    for field in ("display", "power_state"):
        value = environment.get(field)
        if value is None or (
                isinstance(value, str)
                and value.strip().lower() in {"", "unrecorded", "must be recorded by local operator"}
        ):
            errors.append(f"environment.{field} is missing; display and power state must be recorded")

    binaries = manifest.get("binaries")
    if not isinstance(binaries, dict):
        errors.append("run manifest binaries object is missing")
        binaries = {}
    for name in ("production_jar", "native_dylib"):
        entry = binaries.get(name)
        if not isinstance(entry, dict):
            errors.append(f"binaries.{name} identity is missing")
            continue
        path_value = entry.get("path")
        sha256 = entry.get("sha256")
        if not isinstance(path_value, str) or not path_value.strip():
            errors.append(f"binaries.{name}.path is missing")
        if not isinstance(sha256, str) or len(sha256) != 64 or any(ch not in "0123456789abcdefABCDEF" for ch in sha256):
            errors.append(f"binaries.{name}.sha256 is missing or not a SHA-256 digest")
        if name == "production_jar" and isinstance(path_value, str) and "sources" in Path(path_value).name:
            errors.append("binaries.production_jar points to a sources JAR, not the runtime production JAR")

    git = manifest.get("git")
    if not isinstance(git, dict) or not isinstance(git.get("head"), str) or not git.get("head"):
        errors.append("git.head is missing from run manifest")
    return not errors, manifest, errors


def strict_metrics_payload(payload: Any) -> bool:
    """Return whether a cached trial came from the frame-aligned normalizer."""
    if not isinstance(payload, dict):
        return False
    if not isinstance(payload.get("schema_version"), int) or payload.get("schema_version") < 3:
        return False
    window = payload.get("sample_window")
    return isinstance(window, dict) and window.get("available") is True


def fail_closed_trial(payload: Any, reason: str) -> dict[str, Any]:
    """Preserve diagnostics while preventing legacy metrics from pairing."""
    result = dict(payload) if isinstance(payload, dict) else {}
    errors = result.get("identity_errors")
    if not isinstance(errors, list):
        errors = []
    errors.append(reason)
    result["identity_errors"] = errors
    result["complete"] = False
    return result


def load_trial(path: Path) -> dict[str, Any]:
    metrics_path = path / "metrics.json"
    if metrics_path.is_file():
        try:
            payload = json.loads(metrics_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            return fail_closed_trial({}, f"metrics.json is unreadable: {exc}")
    else:
        has_native_report = any(path.rglob("native-fullscreen-baseline.json"))
        payload = (
            normalize_strict_trial(path)
            if has_native_report and normalize_strict_trial is not None
            else normalize_trial(path)
        )
    if not strict_metrics_payload(payload):
        return fail_closed_trial(
            payload,
            "cached trial is not a schema-3 frame-aligned normalization; performance evidence is incomplete",
        )
    return payload


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
    manifest_ok, manifest, manifest_errors = read_run_manifest(root)
    blocks = sorted((root / "trials").glob("block-*"))
    trial_cache: dict[Path, dict[str, Any]] = {}
    block_status: list[dict[str, Any]] = []
    for block in blocks:
        baseline_path = block / "baseline"
        candidate_path = block / "candidate"
        baseline_trial = load_trial(baseline_path)
        candidate_trial = load_trial(candidate_path)
        trial_cache[baseline_path] = baseline_trial
        trial_cache[candidate_path] = candidate_trial
        block_status.append({
            "block": block.name,
            "baseline_complete": bool(baseline_trial.get("complete")),
            "candidate_complete": bool(candidate_trial.get("complete")),
            "complete_pair": bool(baseline_trial.get("complete")) and bool(candidate_trial.get("complete")),
            "baseline_identity_errors": baseline_trial.get("identity_errors", []),
            "candidate_identity_errors": candidate_trial.get("identity_errors", []),
        })
    complete_pair_blocks = [item for item in block_status if item["complete_pair"]]
    comparison: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "root": str(root),
        "correctness_gate": gate,
        "environment_identity": {
            "available": manifest_ok,
            "manifest": manifest,
            "errors": manifest_errors,
        },
        "block_count": len(blocks),
        "complete_pair_block_count": len(complete_pair_blocks),
        "block_status": block_status,
        "metrics": {},
    }

    any_stable_positive = False
    missing_metrics: list[str] = []
    for metric, spec in METRICS.items():
        pairs: list[dict[str, float | None]] = []
        for block in blocks:
            baseline_trial = trial_cache[block / "baseline"]
            candidate_trial = trial_cache[block / "candidate"]
            if not baseline_trial.get("complete") or not candidate_trial.get("complete"):
                continue
            baseline = baseline_trial["metrics"].get(metric, {})
            candidate = candidate_trial["metrics"].get(metric, {})
            if not baseline.get("available") or not candidate.get("available"):
                continue
            before = float(baseline["median"])
            after = float(candidate["median"])
            raw, pct = paired_improvement(before, after, spec["direction"])
            pairs.append({"block": block.name, "before": before, "after": after, "raw_delta": raw,
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
    if not correctness_passed:
        state = "rejected-correctness-gate"
        reason = "correctness gate did not pass"
    elif not manifest_ok:
        state = "blocked-environment"
        reason = "run-manifest environment or artifact identity is incomplete"
    elif mandatory_missing:
        state = "inconclusive-noise"
        reason = "mandatory structured performance metrics are missing"
    elif len(complete_pair_blocks) < MIN_PAIRED_BLOCKS:
        state = "inconclusive-noise"
        reason = (
            f"fewer than {MIN_PAIRED_BLOCKS} complete paired blocks "
            f"({len(complete_pair_blocks)}/{len(blocks)} block directories contain both successful trials)"
        )
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
        "environment_identity_passed": manifest_ok,
        "environment_identity_errors": manifest_errors,
        "any_stable_positive_metric": any_stable_positive,
        "missing_mandatory_metrics": mandatory_missing,
        "guardrail_regressions": guardrail_regressions,
        "block_count": len(blocks),
        "complete_pair_block_count": len(complete_pair_blocks),
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
        "a passing correctness gate, a complete environment/artifact identity, "
        "mandatory structured FPS and p95 metrics, and no guardrail regression.",
        "",
        f"Complete paired blocks: {comparison.get('complete_pair_block_count', 0)} "
        f"of {comparison.get('block_count', 0)}.",
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
        digest = "a" * 64
        (root / "run-manifest.json").write_text(json.dumps({
            "environment": {"display": "self-test-display", "power_state": "self-test-power"},
            "binaries": {
                "production_jar": {"path": "build/libs/metallum.jar", "sha256": digest},
                "native_dylib": {"path": "libmetallum.dylib", "sha256": digest},
            },
            "git": {"head": "self-test"},
        }), encoding="utf-8")
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
                frame_milliseconds = 1_000.0 / fps
                source_report = {
                    "measuredFrameIntervals": 3,
                    "frameTimeStutterCount": 0,
                    "nativeEncoderCountsPerMeasuredFrame": {
                        "measuredFrames": 3,
                        "renderPerFrame": 6.0 if profile == "baseline" else 5.0,
                        "blitPerFrame": 2.0,
                    },
                    "performanceSampleWindow": {
                        "schemaVersion": 1,
                        "frameIdStart": 1,
                        "frameIdEnd": 3,
                        "frameCount": 3,
                        "frameIntervals": [
                            {"frameId": frame_id, "milliseconds": frame_milliseconds}
                            for frame_id in (1, 2, 3)
                        ],
                        "cpuRenderEncode": [
                            {"frameId": frame_id, "milliseconds": 8.0 if profile == "baseline" else 7.0}
                            for frame_id in (1, 2, 3)
                        ],
                        "gpuFrameTimes": [
                            {"frameId": frame_id, "milliseconds": 10.0 if profile == "baseline" else 9.0}
                            for frame_id in (1, 2, 3)
                        ],
                        "gpuCommandBuffers": [
                            {"frameId": frame_id, "submitIndex": frame_id, "milliseconds": 5.0}
                            for frame_id in (1, 2, 3)
                        ],
                        "gpuCommandBufferCount": 3,
                        "gpuFrameCount": 3,
                    },
                }
                source_path = trial / "artifacts" / "validation" / "native-fullscreen-baseline.json"
                source_path.parent.mkdir(parents=True, exist_ok=True)
                source_path.write_text(
                    json.dumps(source_report),
                    encoding="utf-8",
                )
        _, decision = analyze(root)
        assert decision["state"] == "accepted-candidate", decision

        # A pre-frame-ID metrics cache must not be trusted just because it
        # claims complete=true.  Removing it lets the strict native report be
        # regenerated for the following assertions.
        stale_metrics = root / "trials" / "block-001" / "baseline" / "metrics.json"
        stale_metrics.write_text(json.dumps({"schema_version": 2, "complete": True}), encoding="utf-8")
        _, stale_decision = analyze(root)
        assert stale_decision["complete_pair_block_count"] == 3, stale_decision
        assert stale_decision["state"] == "inconclusive-noise", stale_decision
        stale_metrics.unlink()

        # Four block directories are not four paired observations when one
        # arm failed.  The old analyzer could reach a guardrail verdict here
        # because it counted directories instead of complete pairs.
        failed_trial = root / "trials" / "block-004" / "candidate" / "exit-status.txt"
        failed_trial.write_text("1\n", encoding="utf-8")
        failed_trial.with_name("metrics.json").unlink()
        _, incomplete_decision = analyze(root)
        assert incomplete_decision["complete_pair_block_count"] == 3, incomplete_decision
        assert incomplete_decision["state"] == "inconclusive-noise", incomplete_decision

        # Environment identity is part of the paired claim.  A missing power
        # state must block the verdict even when all trial files look healthy.
        manifest = json.loads((root / "run-manifest.json").read_text(encoding="utf-8"))
        manifest["environment"]["power_state"] = "must be recorded by local operator"
        (root / "run-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        # Restore the failed arm so this assertion isolates the environment gate.
        failed_trial.write_text("0\n", encoding="utf-8")
        failed_trial.with_name("metrics.json").unlink()
        _, environment_decision = analyze(root)
        assert environment_decision["state"] == "blocked-environment", environment_decision
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
