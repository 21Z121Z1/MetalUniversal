#!/usr/bin/env python3
"""Run predeclared A/A, observer OFF/ON or ABBA/BAAB blocks through the production driver.

Every trial keeps its raw result. No repeats, post-result exclusions, automatic
promotion or command strings from a configuration file are supported.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import os
import json
from pathlib import Path
import statistics
import subprocess
import sys
import time

from frame_trial_contract import (BACKENDS, MODES, PRODUCERS, WORKLOADS, atomic_json, canonical_hash,
                                  finite_seconds, packaged_identity, parse_json, require, runner_lock,
                                  shader_identity, snapshot_identity, trial_order, validate_pair, verify_inventory)


def build_plan(args) -> dict:
    require(args.a_jar is not None and args.initial_world is not None and args.workload is not None,
            "--a-jar, --initial-world and --workload are required")
    finite_seconds(args.warmup_seconds, 0, 3600, "warmup seconds")
    finite_seconds(args.sample_seconds, 1, 86400, "sample seconds")
    finite_seconds(args.cooldown_seconds, 0, 3600, "cooldown seconds")
    require(15 <= args.target_fps < 260, "target-fps must be finite and explicit")
    require(not args.workload.startswith("I") or args.producer == "iris", "I0/I1 require Iris")
    require(args.workload != "T0" or args.sample_seconds >= 50, "T0 needs at least 50 seconds")
    require(args.workload != "X0" or args.sample_seconds >= 60, "X0 needs at least 60 seconds")
    a = {"artifact": packaged_identity(args.a_jar), "backend": args.a_backend, "producer": args.producer,
         "observer": args.a_observer, "flags": {"reuseEncoderState": args.a_reuse_encoder_state,
                                                  "terrainSliceCache": args.a_terrain_slice_cache}}
    b = {"artifact": packaged_identity(args.b_jar or args.a_jar), "backend": args.b_backend or args.a_backend,
         "producer": args.producer, "observer": args.b_observer or ("timing" if args.protocol == "observer" else args.a_observer),
         "flags": {"reuseEncoderState": args.b_reuse_encoder_state, "terrainSliceCache": args.b_terrain_slice_cache}}
    if args.protocol == "observer":
        require(args.a_observer == "off", "observer protocol needs --a-observer off")
    variants = {"A": a, "B": b}
    validate_pair(args.protocol, variants)
    differences = [key for key in ("artifact", "backend", "observer") if a[key] != b[key]]
    differences.extend("flags." + key for key in a["flags"] if a["flags"][key] != b["flags"][key])
    require(len(differences) <= 1, "change one implementation/actuator dimension per campaign, not several coupled experiments")
    shader = shader_identity(args.shader_pack, args.shader_pack_sha256) if args.producer == "iris" else None
    require(args.producer == "iris" or (args.shader_pack is None and args.shader_pack_sha256 is None), "non-Iris trial has a pack input")
    return {"schemaVersion": 1, "protocol": args.protocol, "variants": variants,
            "declaredDifferences": differences, "order": trial_order(args.protocol, args.blocks),
            "workloadId": args.workload, "warmupNs": round(args.warmup_seconds * 1e9),
            "sampleNs": round(args.sample_seconds * 1e9), "targetFps": args.target_fps,
            "initialWorld": snapshot_identity(args.initial_world), "shaderPack": shader,
            "cacheProtocol": "fresh disposable world and client per trial; OS caches not reset",
            "cooldownSeconds": args.cooldown_seconds, "randomSeed": None, "orderAuthority": "fixed balanced order, not randomized",
            "postResultFiltering": False, "repeatUntilPass": False, "qualityPolicy": "fixed Fabulous/native output/32 render distance"}


def trial_command(args, root, directory, row, variant):
    jar = args.a_jar if row["variant"] == "A" else args.b_jar or args.a_jar
    command = [sys.executable, str(root / "scripts/agent/run_frame_workload.py"),
               "--jar", str(jar.absolute()), "--output", str(directory), "--workload", args.workload,
               "--producer", variant["producer"], "--backend", variant["backend"],
               "--frame-evidence", variant["observer"], "--metrics-only",
               "--initial-world", str(args.initial_world.absolute()), "--warmup-seconds", str(args.warmup_seconds),
               "--sample-seconds", str(args.sample_seconds), "--target-fps", str(args.target_fps)]
    if args.shader_pack is not None:
        command.extend(["--shader-pack", str(args.shader_pack.absolute()), "--shader-pack-sha256", args.shader_pack_sha256])
    for key, flag in (("reuseEncoderState", "--reuse-encoder-state"), ("terrainSliceCache", "--terrain-slice-cache")):
        if variant["flags"][key]: command.append(flag)
    return command


def verify_block(directory: Path) -> dict:
    plan = parse_json((directory / "plan.json").read_bytes())
    block = parse_json((directory / "block.json").read_bytes())
    require(plan.get("schemaVersion") == 1 and block.get("schemaVersion") == 1, "unknown block schema")
    require(block.get("planSha256") == canonical_hash(plan), "trial order/inputs changed after execution")
    order = plan["order"]
    require(order == trial_order(plan["protocol"], len({row["block"] for row in order})), "order is not the predeclared balanced protocol")
    validate_pair(plan["protocol"], plan["variants"])
    require(block.get("complete") is True and len(block["trials"]) == len(order), "block is incomplete; preserve every planned trial")
    summaries, pair_identity, reasons = {"A": [], "B": []}, None, []
    failed_trials = 0
    for expected, execution in zip(order, block["trials"]):
        require(execution.get("order") == expected, "trial was moved, repeated, or filtered")
        name = f"trial-{expected['ordinal']:04d}-{expected['variant']}"
        require(execution.get("directory") == name, "trial directory identity mismatch")
        trial_dir = directory / name
        require(trial_dir.is_dir() and not trial_dir.is_symlink(), "missing/symlinked trial")
        trial = parse_json((trial_dir / "trial-manifest.json").read_bytes())
        require(trial["trialId"] == name and canonical_hash(trial) == execution["manifestSha256"], "trial manifest changed")
        verify_inventory(trial_dir, trial["artifacts"])
        if execution.get("exitCode") != 0 or trial.get("status") != "valid-observation" or trial.get("complete") is not True:
            reasons.append(name + ":failed-or-unavailable; retained without exclusion")
            failed_trials += 1
            summaries[expected["variant"]].append({"trial": name, "block": expected["block"],
                "status": trial.get("status"), "sourceFps": None,
                "unavailableReason": "failed-or-unavailable-trial; no imputed result"})
            continue
        variant = plan["variants"][expected["variant"]]
        require(trial["artifact"] == variant["artifact"] and trial["observerMode"] == variant["observer"], "trial binary/instrument differs from plan")
        for key in ("reuseEncoderState", "terrainSliceCache"):
            require(trial["features"][key] == variant["flags"][key], "trial actuator differs from plan")
        work = trial["workload"]
        require(work["backend"] == variant["backend"] and work["producer"] == variant["producer"]
                and all(work[key] == plan[key] for key in ("workloadId", "warmupNs", "sampleNs", "targetFps", "initialWorld", "shaderPack")),
                "trial work/quality/window differs from plan")
        require(trial["bootstrap"] is False, "bootstrap is not a comparison sample")
        observed = trial["observation"]
        environment = trial["environmentBefore"]
        keys = ("os", "architecture", "hardwareModel", "jdk", "xcode", "sdk", "powerSource", "powerPolicy")
        facts = {key: environment[key].get("value") for key in keys}
        if any(environment[key]["authority"] != "measured" for key in keys): reasons.append(name + ":environment-identity-unavailable")
        identity = {"facts": facts, "display": trial["displayBefore"], "settings": observed["settingsSha256"],
                    "driver": work["driverFiles"], "mods": {k: v for k, v in observed["loadedArtifact"]["mods"].items() if k != "metallum"}}
        if pair_identity is None: pair_identity = identity
        else: require(pair_identity == identity, "paired machine/display/toolchain/quality/producer/driver identities differ")
        if observed.get("transitionWindow"): reasons.append(name + ":transition-workload-is-not-steady-state")
        frame = observed["frameEvidence"]
        if frame.get("status") != "comparison-ready": reasons.append(name + ":presentation-comparison-not-ready")
        summaries[expected["variant"]].append({"trial": name, "block": expected["block"],
            "sourceFps": observed["sourceSampleWindow"]["fps"], "sourceIntervalP99UpperBoundMs": observed["sourceSampleWindow"].get("intervalP99UpperBoundMs"),
            "serverTicks": observed["serverTicks"]})
    stats = {}
    for variant, values in summaries.items():
        stats[variant] = {"trials": values, "count": len(values),
                          "sourceFpsMeanAcrossTrials": statistics.mean(x["sourceFps"] for x in values) if values and not failed_trials else None,
                          "aggregateUnavailableReason": "failed-trial-prevents-unfiltered-aggregate" if failed_trials else None}
    # No confidence interval, non-inferiority margin or superiority is invented from a handful of trials.
    return {"status": "invalid-evidence" if failed_trials else "valid-observation" if reasons else "comparison-ready", "reasons": reasons,
            "failedTrialCount": failed_trials,
            "protocol": plan["protocol"], "planSha256": canonical_hash(plan), "variants": stats,
            "filtering": "all planned trials retained; any failure prevents comparison-ready",
            "statisticalUnit": "trial/block, not independent per-frame samples",
            "physicalPerformanceAcceptance": "physical-validation-required", "productPromotable": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", type=Path, help="Verify an existing block without executing anything")
    parser.add_argument("--protocol", choices=("aa", "abba", "baab", "observer"), default="aa")
    parser.add_argument("--blocks", type=int, default=2)
    parser.add_argument("--a-jar", type=Path); parser.add_argument("--b-jar", type=Path)
    parser.add_argument("--a-backend", choices=BACKENDS, default="metal3"); parser.add_argument("--b-backend", choices=BACKENDS)
    parser.add_argument("--a-observer", choices=MODES, default="timing"); parser.add_argument("--b-observer", choices=MODES)
    parser.add_argument("--producer", choices=PRODUCERS, default="vanilla")
    for variant in ("a", "b"):
        parser.add_argument(f"--{variant}-reuse-encoder-state", action="store_true")
        parser.add_argument(f"--{variant}-terrain-slice-cache", action="store_true")
    parser.add_argument("--initial-world", type=Path); parser.add_argument("--workload", choices=WORKLOADS)
    parser.add_argument("--shader-pack", type=Path); parser.add_argument("--shader-pack-sha256")
    parser.add_argument("--warmup-seconds", type=float, default=30); parser.add_argument("--sample-seconds", type=float, default=120)
    parser.add_argument("--target-fps", type=int, default=60); parser.add_argument("--cooldown-seconds", type=float, default=60)
    parser.add_argument("--output", type=Path); parser.add_argument("--preflight-only", action="store_true")
    args = parser.parse_args()
    if args.verify:
        try:
            result = verify_block(args.verify)
            print(json.dumps(result, indent=2))
            return 0 if not any("failed-or-unavailable" in reason for reason in result["reasons"]) else 1
        except (OSError, ValueError, KeyError, TypeError) as error:
            print(json.dumps({"status": "invalid-evidence", "reason": str(error)}))
            return 1
    if args.output is None: parser.error("--output is required")
    plan = build_plan(args)
    root = Path(__file__).resolve().parents[2]
    output = args.output.absolute(); output.mkdir(parents=True, exist_ok=False)
    atomic_json(output / "plan.json", plan)
    block = {"schemaVersion": 1, "planSha256": canonical_hash(plan), "complete": False,
             "startedAt": datetime.now(timezone.utc).isoformat(), "trials": [], "preflightOnly": args.preflight_only}
    atomic_json(output / "block.json", block)
    if args.preflight_only:
        print(output / "plan.json")
        return 0  # A plan is intentionally not a complete evidence block.
    with runner_lock(root) as lock:
        try:
            for row in plan["order"]:
                name = f"trial-{row['ordinal']:04d}-{row['variant']}"
                command = trial_command(args, root, output / name, row, plan["variants"][row["variant"]])
                with (output / (name + "-launcher.log")).open("x") as log:
                    completed = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, check=False,
                        env={**os.environ, "METALLUM_FRAME_LOCK_FD": str(lock.fileno())}, pass_fds=(lock.fileno(),))
                manifest_path = output / name / "trial-manifest.json"
                receipt = parse_json(manifest_path.read_bytes()) if manifest_path.exists() else None
                block["trials"].append({"order": row, "directory": name, "exitCode": completed.returncode,
                                        "manifestSha256": canonical_hash(receipt) if receipt else None})
                atomic_json(output / "block.json", block)
                # Fixed before results; never extend cooldown until an outlier disappears.
                if row != plan["order"][-1]: time.sleep(args.cooldown_seconds)
            block["complete"] = True
        finally:
            block["finishedAt"] = datetime.now(timezone.utc).isoformat()
            atomic_json(output / "block.json", block)
    try:
        result = verify_block(output)
    except (OSError, ValueError, KeyError, TypeError) as error:
        result = {"status": "invalid-evidence", "reason": str(error)}
    atomic_json(output / "verification.json", result)
    print(output / "verification.json")
    return 1 if result["status"] == "invalid-evidence" or any("failed-or-unavailable" in x for x in result.get("reasons", [])) else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
