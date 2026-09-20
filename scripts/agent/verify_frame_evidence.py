#!/usr/bin/env python3
"""Check P0 observation integrity; never approves rendering correctness or performance."""
import argparse
import copy
import json
import math
import re
from pathlib import Path


def integer(value, minimum=0):
    if type(value) is not int or value < minimum:
        raise ValueError(f"expected integer >= {minimum}: {value!r}")
    return value


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify(report, expected_head, require_packaged=False):
    require(re.fullmatch(r"[0-9a-f]{40}", expected_head) is not None, "expected HEAD must be a full SHA")
    require(report["schemaVersion"] == 1, "unsupported schema")
    identity = report["identity"]
    build = identity["build"]
    require(build["sourceSha"] == expected_head, "embedded source SHA differs from expected HEAD")
    require(re.fullmatch(r"[0-9a-f]{40}", build["treeSha"]) is not None, "missing source tree")
    require(build["dirty"] is False, "dirty/unknown build cannot prove exact HEAD")
    require(identity["requestedSourceSha"] in ("unknown", expected_head), "requested SHA conflicts with embedded provenance")
    require(identity["mods"]["minecraft"] == build["minecraftVersion"], "Minecraft build/runtime mismatch")
    require(identity["backend"].lower() == "metal", "not the Metal backend")
    require(identity["trialId"] not in ("", "unspecified"), "missing trial identity")
    require(re.fullmatch(r"[0-9a-f]{64}", identity["nativeSha256"]) is not None, "missing loaded native identity")
    packaged = re.fullmatch(r"[0-9a-f]{64}", identity["javaArtifactSha256"]) is not None
    require(packaged or (not require_packaged and identity["javaArtifactSha256"] == "unavailable-dev-classes"),
            "missing packaged Java artifact identity")
    require(report["scope"] == "Minecraft.renderFrame/render-thread/main-command-queue", "unknown observation scope")
    require(report["shutdownDrained"] is True, "missing shutdown drain")
    require(report["validationStatus"] == "passed", "client validation did not pass")
    require(integer(report["droppedFrames"]) == 0, "bounded capture lost frames")
    require(bool(report["frames"]), "empty capture")
    previous_frame = 0
    seen_frames = set()
    seen_submissions = set()
    cpu, crossings, abi_time, gpu_service = [], [], [], []
    for frame in report["frames"]:
        frame_id = integer(frame["frameId"], 1)
        require(frame_id > previous_frame, "duplicate/reordered frame ID")
        previous_frame = frame_id
        parent = integer(frame["parentFrameId"])
        require(parent == 0 or parent in seen_frames, "unknown/nonpreceding parent frame")
        seen_frames.add(frame_id)
        require(frame["ended"] is True and frame["failure"] == "", "incomplete/invalid frame")
        duration = integer(frame["cpuFrameNs"], 1)
        require(type(frame["renderLevel"]) is bool, "invalid renderLevel")
        require(isinstance(frame["producerEntries"], list) and all(isinstance(p, str) for p in frame["producerEntries"]),
                "invalid producer labels")
        calls, exclusive = 0, 0
        for symbol, counter in frame["abi"].items():
            # The existing native ABI also exports legacy MTL* fence symbols.
            # A name is an observed symbol, not a semantic classification/allowlist.
            require(re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", symbol) is not None, "invalid ABI symbol")
            calls += integer(counter["calls"], 1)
            inclusive = integer(counter["inclusiveNs"])
            own_time = integer(counter["exclusiveNs"])
            require(inclusive >= own_time, "exclusive ABI time exceeds inclusive time")
            require(integer(counter["failures"]) == 0, "failed ABI call")
            exclusive += own_time
        require(exclusive <= duration, "ABI time exceeds its owning frame")
        for submission in frame["commandBuffers"]:
            sid = integer(submission["submissionId"], 1)
            require(sid not in seen_submissions, "duplicate submission identity")
            seen_submissions.add(sid)
            integer(submission["nativeSubmitIndex"])
            require(all(submission[k] is True for k in ("submitted", "completed", "success")), "pending/failed command buffer")
            ns = submission["gpuServiceNs"]
            if ns is not None:
                integer(ns, 1)
            require(submission["gpuUnavailableReason"] == ("" if ns is not None else "gpu-timestamp-unavailable"),
                    "GPU availability contradicts completion/timestamp")
            if frame["renderLevel"] and ns is not None:
                gpu_service.append(ns)
        if frame["renderLevel"]:
            cpu.append(duration)
            crossings.append(calls)
            abi_time.append(exclusive)
    require(bool(cpu) and sum(crossings) > 0 and bool(seen_submissions), "no world-frame Metal activation")
    require(bool(report["unavailable"]), "missing measurement coverage boundary")

    def summary(values):
        values = sorted(values)
        return {"samples": len(values), **{f"p{q}": values[math.ceil(q / 100 * len(values)) - 1]
                                         if values else None for q in (50, 95, 99)}}

    return {"status": "valid-observation-no-performance-decision", "sourceSha": expected_head,
            "packagedJavaIdentity": packaged, "worldFrames": len(cpu),
            "cpuFrameNs": summary(cpu), "renderThreadAbiCrossings": summary(crossings),
            "renderThreadAbiExclusiveNs": summary(abi_time), "commandBufferGpuServiceNs": summary(gpu_service),
            "unavailable": report["unavailable"]}


def self_test():
    head = "a" * 40
    fixture = {"schemaVersion": 1, "identity": {
        "build": {"sourceSha": head, "treeSha": "b" * 40, "dirty": False, "minecraftVersion": "26.3"},
        "requestedSourceSha": head, "mods": {"minecraft": "26.3"}, "backend": "Metal", "trialId": "fixture",
        "nativeSha256": "c" * 64, "javaArtifactSha256": "d" * 64},
        "scope": "Minecraft.renderFrame/render-thread/main-command-queue", "shutdownDrained": True,
        "validationStatus": "passed", "droppedFrames": 0, "unavailable": {"gpuFrameNs": "not command-buffer time"},
        "frames": [{"frameId": 1, "parentFrameId": 0, "cpuFrameNs": 100, "renderLevel": True, "ended": True, "failure": "",
                    "producerEntries": ["vanilla-terrain-layer-return"],
                    "abi": {"metallum_draw": {"calls": 2, "inclusiveNs": 50, "exclusiveNs": 40, "failures": 0}},
                    "commandBuffers": [{"submissionId": 1, "nativeSubmitIndex": 0, "submitted": True,
                                        "completed": True, "success": True, "gpuServiceNs": 80,
                                        "gpuUnavailableReason": ""}]}]}
    assert verify(fixture, head, True)["cpuFrameNs"]["p99"] == 100
    legacy = copy.deepcopy(fixture)
    legacy["frames"][0]["abi"]["MTLRenderCommandEncoder_waitForFence"] = legacy["frames"][0]["abi"].pop("metallum_draw")
    assert verify(legacy, head)["renderThreadAbiCrossings"]["p50"] == 2
    unavailable = copy.deepcopy(fixture)
    unavailable["frames"][0]["commandBuffers"][0].update(gpuServiceNs=None, gpuUnavailableReason="gpu-timestamp-unavailable")
    assert verify(unavailable, head)["commandBufferGpuServiceNs"]["samples"] == 0
    mutations = [
        lambda x: x["identity"]["build"].update(sourceSha="e" * 40),
        lambda x: x["identity"]["build"].update(dirty=True),
        lambda x: x.update(droppedFrames=1),
        lambda x: x.update(validationStatus="failed"),
        lambda x: x["frames"].append(copy.deepcopy(x["frames"][0])),
        lambda x: x["frames"][0].update(cpuFrameNs=True),
        lambda x: x["frames"][0].update(cpuFrameNs=30),
        lambda x: x["frames"][0].update(ended=False),
        lambda x: x["frames"][0].update(parentFrameId=1),
        lambda x: x["frames"][0]["abi"]["metallum_draw"].update(failures=1),
        lambda x: x["frames"][0]["abi"]["metallum_draw"].update(exclusiveNs=51),
        lambda x: x["frames"][0]["commandBuffers"][0].update(completed=False),
        lambda x: x["frames"][0]["commandBuffers"][0].update(success=False),
        lambda x: x["frames"][0]["commandBuffers"][0].update(gpuServiceNs=0),
    ]
    for mutate in mutations:
        broken = copy.deepcopy(fixture)
        mutate(broken)
        try:
            verify(broken, head)
        except (ValueError, KeyError, TypeError):
            continue
        raise AssertionError("invalid evidence accepted")
    print("Frame evidence self-test: PASS")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON field: {key}")
        result[key] = value
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, nargs="?")
    parser.add_argument("--expected-head")
    parser.add_argument("--require-packaged", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        if args.report is None or args.expected_head is None:
            parser.error("report and --expected-head are required")
        try:
            report = json.loads(args.report.read_text(), object_pairs_hook=unique_object,
                                parse_constant=lambda value: require(False, f"non-finite JSON value: {value}"))
            print(json.dumps(verify(report, args.expected_head, args.require_packaged), indent=2))
        except (ValueError, KeyError, TypeError, OSError) as error:
            print(json.dumps({"status": "rejected-frame-evidence", "reason": str(error)}))
            raise SystemExit(1)
