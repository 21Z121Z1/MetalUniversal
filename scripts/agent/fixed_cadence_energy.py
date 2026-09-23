#!/usr/bin/env python3
"""Integrate instrument-provided interval power in the existing source window.

This consumes a real instrument/exporter's trace; it never infers watts from
FPS, CPU utilization, battery percentage or generated frames.
"""
from __future__ import annotations

import math
from pathlib import Path
import shutil
import statistics

from frame_trial_contract import atomic_json, canonical_hash, parse_json, require, sha256

PROFILE = {
    "id": "fixed-cadence-energy-v1", "version": 1,
    "minimumPairedBlocks": 4, "minimumPositiveFraction": 0.75,
    "minimumTargetFraction": 0.98, "sourceFpsRegressionMax": 0.02,
    "sourceP99RegressionMax": 0.02, "maximumClockUncertaintyNs": 1_000_000,
    "maximumSampleIntervalNs": 1_000_000_000, "minimumWarmupNs": 30_000_000_000,
    "minimumSampleNs": 120_000_000_000,
    "quality": "native-output/full-quality/unchanged-render-distance",
    "energyDenominator": "elapsed-source-window-seconds; never generated/presented frames",
    "correctnessAndGuardrails": "existing unified contract remains mandatory for product acceptance",
}


def number(value):
    require(type(value) in (int, float) and math.isfinite(value), "non-finite/non-numeric power")
    return float(value)


def integrate(trace: dict, trial_id: str, artifact: dict, window: dict) -> dict:
    require(trace.get("schemaVersion") == 1, "unknown power trace schema")
    require(trace.get("trialId") == trial_id and trace.get("artifact") == artifact,
            "power trace belongs to another trial/binary")
    expected = {key: window[key] for key in ("clock", "startNs", "endNs")}
    require(expected["clock"] == "System.nanoTime" and trace.get("sourceWindow") == expected,
            "power/source sample windows differ")
    start, end = expected["startNs"], expected["endNs"]
    require(type(start) is int and type(end) is int and end > start, "invalid source window")
    source = trace["source"]
    require(source.get("domain") in ("cpu", "gpu", "soc", "wall-system"), "unknown measurement domain")
    require(source.get("authority") in ("calibrated-instrument", "platform-reported"), "power source is not measured")
    require(all(isinstance(source.get(key), str) and source[key].strip() for key in ("id", "version", "method")),
            "power source provenance is missing")
    require(source.get("unit") == "W" and trace.get("sampleKind") == "interval-mean", "unsupported power units/sample kind")
    sync = trace["clockMapping"]
    require(isinstance(sync.get("method"), str) and bool(sync["method"].strip())
            and isinstance(sync.get("evidence"), str) and bool(sync["evidence"].strip()), "missing instrument/source clock correlation")
    require(type(sync.get("maximumErrorNs")) is int and 0 <= sync["maximumErrorNs"] <= PROFILE["maximumClockUncertaintyNs"],
            "power clock correlation is too uncertain")
    # No extrapolation, gaps, reordering, overlap, imputed samples or rescaling
    # of a partially covered sensor interval. Boundaries must be exported at
    # the requested source window by the instrument's correlation step.
    cursor, joules, count = start, 0.0, 0
    for sample in trace["samples"]:
        left, right = sample["startNs"], sample["endNs"]
        require(type(left) is int and type(right) is int and left == cursor and left < right <= end,
                "power intervals have a gap, overlap, reorder or out-of-window sample")
        require(right - left <= PROFILE["maximumSampleIntervalNs"], "power sampling interval is too coarse")
        watts = number(sample["meanWatts"])
        require(watts >= 0, "negative measured power")
        joules += watts * ((right - left) / 1e9)
        cursor, count = right, count + 1
    require(count > 0 and cursor == end and math.isfinite(joules), "incomplete power coverage")
    return {"available": True, "profile": PROFILE["id"], "source": source,
            "sourceWindow": expected, "clockMapping": sync, "sampleCount": count,
            "energyJoules": joules, "meanPowerWatts": joules * 1e9 / (end - start),
            "integration": "sum(interval-mean watts * exact covered seconds)",
            "scope": source["domain"], "wholeSystemClaim": source["domain"] == "wall-system"}


def collect(path: Path | None, output: Path, trial_id: str, artifact: dict, window: dict) -> dict:
    if path is None or not path.is_file():
        result = {"available": False, "profile": PROFILE["id"],
                  "reason": "no instrument power trace covering the exact source-clock window"}
        atomic_json(output / "energy.json", result)
        return result
    require(not path.is_symlink(), "power trace cannot be a symlink")
    target = output / "power-trace.json"
    before = sha256(path)
    shutil.copyfile(path, target)
    require(before == sha256(path) == sha256(target), "instrument trace changed while importing")
    result = integrate(parse_json(target.read_bytes()), trial_id, artifact, window)
    result["traceSha256"] = before
    atomic_json(output / "energy.json", result)
    return result


def compare(plan: dict, trials: list[dict], comparison_ready: bool) -> dict:
    result = {"profile": PROFILE, "status": "unavailable", "productPromotable": False,
              "reason": "missing power or comparison-ready frame evidence", "pairedBlocks": 0}
    if not comparison_ready or plan["protocol"] not in ("aa", "abba", "baab"):
        return result
    if plan["sampleNs"] < PROFILE["minimumSampleNs"] or plan["warmupNs"] < PROFILE["minimumWarmupNs"]:
        result["reason"] = "profile requires 30 s warmup and 120 s measurement"
        return result
    blocks, source_identity = {}, None
    cadence_regression = False
    for execution in trials:
        observation = execution["observation"]
        energy = observation.get("energy", {})
        if not energy.get("available"): return result
        identity = canonical_hash(energy["source"])
        if source_identity is None: source_identity = identity
        require(identity == source_identity, "paired power source/domain/method differs")
        require(energy["profile"] == PROFILE["id"], "power profile changed")
        window = observation["sourceSampleWindow"]
        require(energy["sourceWindow"] == {key: window[key] for key in ("clock", "startNs", "endNs")},
                "normalized energy window differs from source sample")
        duration = window["endNs"] - window["startNs"]
        require(duration == plan["sampleNs"], "energy comparison changed the fixed experience duration")
        for field in ("energyJoules", "meanPowerWatts"):
            require(number(energy[field]) >= 0, "invalid normalized energy")
        fps = number(window["fps"])
        require(math.isclose(energy["energyJoules"], energy["meanPowerWatts"] * duration / 1e9, rel_tol=1e-12, abs_tol=1e-12),
                "energy/power/duration disagree")
        cadence_regression |= fps < plan["targetFps"] * PROFILE["minimumTargetFraction"]
        p99 = window.get("intervalP99UpperBoundMs")
        if p99 is None:
            result["reason"] = "source interval P99 is unavailable"
            return result
        group = blocks.setdefault(execution["block"], {"A": [], "B": []})
        group[execution["variant"]].append((energy["energyJoules"], fps, number(p99)))
    pairs = []
    for block, group in sorted(blocks.items()):
        # Each ABBA block contains two observations per arm; A/A has two A's.
        if plan["protocol"] == "aa":
            require(len(group["A"]) == 2 and not group["B"], "invalid energy A/A block")
            a, b = group["A"]
        else:
            require(len(group["A"]) == len(group["B"]) == 2, "incomplete energy paired block")
            a = tuple(statistics.mean(row[i] for row in group["A"]) for i in range(3))
            b = tuple(statistics.mean(row[i] for row in group["B"]) for i in range(3))
        require(a[0] > 0 and a[1] > 0 and a[2] > 0, "invalid zero baseline")
        cadence_regression |= (min(a[1], b[1]) < plan["targetFps"] * PROFILE["minimumTargetFraction"]
                or b[1] < a[1] * (1 - PROFILE["sourceFpsRegressionMax"])
                or b[2] > a[2] * (1 + PROFILE["sourceP99RegressionMax"]))
        pairs.append({"block": block, "beforeJoules": a[0], "afterJoules": b[0],
                      "rawChangeJoules": b[0] - a[0], "improvementPercent": (a[0] - b[0]) / a[0] * 100})
    if not pairs: return result
    improvement = statistics.median(p["improvementPercent"] for p in pairs)
    positive = sum(p["improvementPercent"] > 0 for p in pairs)
    result.update(pairedBlocks=len(pairs), pairs=pairs, pairedMedianImprovementPercent=improvement,
                  positiveBlocks=positive, measurementSource=trials[0]["observation"]["energy"]["source"],
                  cadenceGuardrailPassed=not cadence_regression)
    if plan["protocol"] == "aa":
        result.update(status="aa-observation", reason="observer/noise characterization; no benefit claim")
    elif cadence_regression:
        result.update(status="rejected-regression", reason="fixed source cadence or source P99 guardrail failed")
    elif len(pairs) < PROFILE["minimumPairedBlocks"]:
        result.update(status="inconclusive-noise", reason="fewer than four paired blocks")
    elif improvement > 0 and positive / len(pairs) >= PROFILE["minimumPositiveFraction"]:
        result.update(status="energy-direction-passed", reason="measured domain improved; independent correctness, activation, A/A and CPU/GPU/memory/stutter gates still required")
    else:
        result.update(status="inconclusive-noise", reason="energy did not improve consistently")
    return result
