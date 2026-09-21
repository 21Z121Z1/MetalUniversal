#!/usr/bin/env python3
"""Check P0 observation integrity; never approves rendering correctness or performance."""
import argparse
import copy
import json
import math
import hashlib
import tempfile
import re
from pathlib import Path


def integer(value, minimum=0):
    if type(value) is not int or value < minimum:
        raise ValueError(f"expected integer >= {minimum}: {value!r}")
    return value


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify(report, expected_head, require_packaged=False, require_comparable=False, artifact_root=None):
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
    mode = identity.get("instrumentationMode", "diagnostic")
    require(mode in ("diagnostic", "timing"), "unknown instrumentation mode")
    previous_frame = 0
    seen_frames = set()
    seen_submissions = set()
    seen_presentations = set()
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
        if "terrainBatchIndices" in frame:
            batches = frame["terrainBatchIndices"]
            require(isinstance(batches, list) and len(batches) <= 64, "invalid terrain batch evidence")
            for batch in batches:
                integer(batch)
            require(len(set(batches)) == len(batches), "duplicate terrain batch index in source frame")
            require(not batches or "vanilla-terrain-layer-return" in frame["producerEntries"],
                    "terrain batch without Vanilla layer encoding evidence")
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
        require(mode != "timing" or not frame["abi"], "timing mode must not claim ABI diagnostics")
        for submission in frame["commandBuffers"]:
            sid = integer(submission["submissionId"], 1)
            require(sid not in seen_submissions, "duplicate submission identity")
            seen_submissions.add(sid)
            integer(submission["nativeSubmitIndex"])
            # Additive v1 fields: older captures remain readable, but partial/contradictory
            # presentation receipts must not turn a requested present into display evidence.
            if {"presentationRequested", "nativePresentationId", "presentationIdUnavailableReason"} & submission.keys():
                requested = submission["presentationRequested"]
                require(type(requested) is bool, "invalid presentation request")
                present_id = submission["nativePresentationId"]
                if present_id is None:
                    reason = "native-present-id-not-returned" if requested else "no-presentation-request"
                else:
                    integer(present_id, 1)
                    require(requested and present_id not in seen_presentations, "unrequested/duplicate native presentation ID")
                    seen_presentations.add(present_id)
                    reason = ""
                require(submission["presentationIdUnavailableReason"] == reason, "contradictory presentation ID availability")
            if "presentedTimeSeconds" in submission:
                timestamp = submission["presentedTimeSeconds"]
                reason = submission["presentedUnavailableReason"]
                if timestamp is not None:
                    require(type(timestamp) in (int, float) and math.isfinite(timestamp) and timestamp > 0,
                            "invalid drawable presented timestamp")
                    require(submission["presentationRequested"] is True and submission["nativePresentationId"] is not None,
                            "presented timestamp has no native ticket")
                    require(reason == "", "presented timestamp contradicts absence reason")
                else:
                    require(bool(reason), "missing presented timestamp needs an absence reason")
            require(all(submission[k] is True for k in ("submitted", "completed", "success")), "pending/failed command buffer")
            if "drawableWaitNs" in submission:
                wait = submission["drawableWaitNs"]
                if wait is not None:
                    integer(wait)
                require(submission["drawableWaitUnavailableReason"] == (
                    "" if wait is not None else "not-observed-or-native-unavailable"),
                    "drawable wait contradicts absence reason")
            counters = submission.get("nativeEncoding")
            if counters is not None:
                require(set(counters) == {"renderEncoders", "computeEncoders", "blitEncoders", "directDraws", "indirectDraws"},
                        "invalid native encoding counter layout")
                for count in counters.values():
                    integer(count)
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
    require(bool(cpu) and (mode == "timing" or sum(crossings) > 0) and bool(seen_submissions),
            "no world-frame Metal activation")
    require(bool(report["unavailable"]), "missing measurement coverage boundary")

    def summary(values):
        values = sorted(values)
        return {"samples": len(values), **{f"p{q}": values[math.ceil(q / 100 * len(values)) - 1]
                                         if values else None for q in (50, 95, 99)}}

    delivery = verify_window(report, packaged, artifact_root)
    if require_packaged and "window" in report:
        require("native-build-provenance-unavailable" not in delivery["comparisonEligibility"]["reasons"],
                "packaged window lacks matching native build provenance")
    require(not require_comparable or delivery["comparisonEligibility"]["eligible"],
            "comparison prerequisites unavailable: " + ", ".join(delivery["comparisonEligibility"]["reasons"]))
    abi_crossings = summary(crossings)
    abi_exclusive = summary(abi_time)
    if mode == "timing":
        # Empty instrumentation is missing measurement, not zero native work.
        abi_crossings = {**summary([]), "unavailableReason": "timing-mode-does-not-instrument-ABI"}
        abi_exclusive = dict(abi_crossings)
    return {"status": "valid-observation-no-performance-decision", "sourceSha": expected_head,
            "instrumentationMode": mode, "frameDelivery": delivery,
            "legacyDiagnosticScope": "renderLevel scopes including nested scopes; CPU and GPU service are not presentation",
            "packagedJavaIdentity": packaged, "worldFrames": len(cpu),
            "cpuFrameNs": summary(cpu), "renderThreadAbiCrossings": abi_crossings,
            "renderThreadAbiExclusiveNs": abi_exclusive, "commandBufferGpuServiceNs": summary(gpu_service),
            "unavailable": report["unavailable"]}



def verify_window(report, packaged, artifact_root):
    """Use only declared source membership and actual callback authority.

    Java source rate and native callback event-span rate use separate clocks.
    The callback cohort includes completed callbacks after the source window:
    there is deliberately no attempt to map its endpoints onto the Java clock.
    """
    reasons = []
    result = {"comparisonEligibility": {"eligible": False, "reasons": reasons,
              "scope": "controlled-physical-baseline-prerequisites-only"},
              "physicalPerformanceAcceptance": "unverified",
              "systemDeadline": {"available": False,
                                 "reason": "ordinary path has no authoritative DisplayLink deadline"}}
    window = report.get("window")
    if window is None:
        reasons.append("legacy-capture-without-explicit-window")
        return result
    native_build = report["identity"].get("nativeBuild")
    if native_build is None:
        reasons.append("native-build-provenance-unavailable")
    else:
        require(native_build.get("schemaVersion") == 1, "unknown native provenance schema")
        require(native_build.get("build") == report["identity"]["build"], "Java/native build provenance mismatch")
        require(native_build.get("nativeSha256") == report["identity"]["nativeSha256"], "native artifact/provenance digest mismatch")
    require(window["armed"] is True and window["closed"] is True, "unarmed/incomplete measurement window")
    integer(window["epoch"], 1)
    require(window["clock"] == "java-System.nanoTime", "unknown source clock")
    require(window["membership"] == "root-source-start-half-open; nested-inherits-parent", "unknown window membership")
    # nanoTime has an arbitrary origin and may be negative.
    start, end = window["startNs"], window["endNs"]
    require(type(start) is int and type(end) is int and end > start, "invalid finite source window")
    integer(window["warmupNs"])
    frames = {frame["frameId"]: frame for frame in report["frames"]}
    roots, crossing_end, requested, observed = [], 0, 0, []
    missing = {}
    baseline_context = None
    quality_keys = ("drawableWidth", "drawableHeight", "internalWidth", "internalHeight", "renderDistance",
                    "targetFps", "vsync")
    nested_presentations = False
    for frame in report["frames"]:
        require(type(frame["epoch"]) is int and frame["epoch"] == window["epoch"], "cross-epoch frame")
        began = frame["sourceStartNs"]
        require(type(began) is int, "invalid source start clock")
        parent_id = frame["parentFrameId"]
        if parent_id == 0:
            require(start <= began < end, "root frame outside declared sample window")
            roots.append(frame)
            crossing_end += began + frame["cpuFrameNs"] > end
        else:
            parent = frames[parent_id]
            require(parent["sourceStartNs"] <= began
                    and began + frame["cpuFrameNs"] <= parent["sourceStartNs"] + parent["cpuFrameNs"],
                    "nested scope does not fit its owning source interval")
        context = frame.get("context", {})
        if not all(key in context for key in quality_keys):
            if "quality-or-target-context-unavailable" not in reasons:
                reasons.append("quality-or-target-context-unavailable")
        else:
            values = {key: context[key] for key in quality_keys}
            for key in quality_keys[:5]:
                integer(values[key], 1)
            integer(values["targetFps"], 1)
            require(type(values["vsync"]) is bool, "invalid vsync intent")
            if baseline_context is None:
                baseline_context = values
            require(values == baseline_context, "quality/size/target intent changed inside sample window")
        for submission in frame["commandBuffers"]:
            if submission.get("presentationRequested") is not True:
                continue
            requested += 1
            nested_presentations |= parent_id != 0
            stamp = submission.get("presentedTimeSeconds")
            if stamp is None:
                reason = submission.get("presentedUnavailableReason", "callback-field-unavailable")
                missing[reason] = missing.get(reason, 0) + 1
            else:
                observed.append(stamp)
    require(bool(roots), "window has no root source frames")
    require(all(frame["renderLevel"] is True for frame in roots), "window contains non-world root source frames")
    observed.sort()
    coincident = len(observed) - len(set(observed))
    if coincident:
        reasons.append("non-unique-presented-timestamps-cannot-count-distinct-display-events")
    intervals = sorted((b - a) * 1e9 for a, b in zip(observed, observed[1:]))
    require(all(math.isfinite(value) and value >= 0 for value in intervals), "invalid native presentation interval")
    quantiles = {f"p{q}": intervals[math.ceil(q / 100 * len(intervals)) - 1] if intervals else None
                 for q in (50, 95, 99)}
    quantiles["p99.9"] = intervals[math.ceil(.999 * len(intervals)) - 1] if len(intervals) >= 1000 and not coincident else None
    quantiles["p99.9UnavailableReason"] = ("non-unique-presented-timestamps" if coincident else
            "" if len(intervals) >= 1000 else "fewer-than-predeclared-1000-intervals")
    if missing:
        reasons.append("incomplete-actual-presentation-coverage")
    if len(observed) < 2:
        reasons.append("fewer-than-two-actual-presentations")
    if nested_presentations:
        reasons.append("nested-scope-presentation-cohort-needs-independent-comparison-proof")
    if not packaged:
        reasons.append("packaged-java-identity-unavailable")
    profile = window.get("profile")
    if not isinstance(profile, dict) or not profile:
        reasons.append("replay-profile-unavailable")
    else:
        verify_profile(profile, baseline_context, window, report["identity"], reasons, artifact_root)
        # This fixed-view ordinary path encodes one real drawable request per root.
        # Callback coverage alone cannot detect an omitted carry-in command buffer.
        if profile.get("profileId") == "vanilla-stationary-60-v1" and any(
                sum(submission.get("presentationRequested") is True
                    for submission in frame["commandBuffers"]) != 1 for frame in roots):
            reasons.append("stationary-source-presentation-request-coverage-incomplete")
    require("iris" not in report["identity"]["mods"] and "sodium" not in report["identity"]["mods"],
            "ordinary Vanilla window includes optional render mods")
    result.update({"epoch": window["epoch"], "sourceClock": window["clock"],
                   "sourceRootCount": len(roots), "sourceWindowDurationNs": end - start,
                   "sourceCountRateHz": len(roots) * 1e9 / (end - start),
                   "sourceScopesCrossingEnd": crossing_end,
                   "presentationCohort": "all callbacks of window-owned source scopes, including callbacks after source-window end",
                   "actualPresentationClock": "CAMetalDrawable.presentedTime-seconds",
                   "actualDrawableCallbackCount": len(observed),
                   "coincidentPresentedTimestampCount": coincident,
                   "actualPresentationCount": None if coincident else len(observed),
                   "actualPresentationCountUnavailableReason": "non-unique-presented-timestamps" if coincident else "",
                   "requestedPresentationCount": requested,
                   "missingPresentationReasons": missing,
                   "actualPresentEventSpanRateHz": ((len(observed) - 1) / (observed[-1] - observed[0])) if len(observed) >= 2 and not coincident else None,
                   "actualPresentEventSpanRateScope": "first-to-last callback event span; not the Java source window or a display refresh rate",
                   "presentIntervalNs": {"samples": len(intervals), **quantiles,
                                         "scope": "callback timestamp intervals including zeros; distinct display events ambiguous" if coincident else
                                         "complete cohort" if not missing else "observed callbacks only; missing events can merge intervals"}})
    result["comparisonEligibility"]["eligible"] = not reasons
    if isinstance(profile, dict):
        result["comparisonIdentity"] = {
            "initialContentSha256": profile.get("initialContent", {}).get("snapshotSha256"),
            "quality": profile.get("quality"), "targetIntent": profile.get("targetIntent"),
            "route": profile.get("route"), "instrumentationMode": report["identity"].get("instrumentationMode"),
            "scope": "pairs must additionally match content/quality/target/route; eligibility is not a pairwise or physical verdict"}
    return result


def verify_profile(profile, context, window, identity, reasons, artifact_root):
    # Profile schema is checked separately from a caller-supplied source SHA.
    # Absent replay facts lower comparison eligibility, never become defaults.
    require(profile.get("instrumentationMode") == identity.get("instrumentationMode"),
            "profile/runtime instrumentation mode mismatch")
    if not isinstance(profile.get("initialContent"), dict):
        reasons.append("initial-content-identity-unavailable")
    else:
        digest = profile["initialContent"].get("snapshotSha256", "")
        require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest) is not None,
                "invalid initial content snapshot identity")
        require(profile["initialContent"].get("kind") == "flushed-world-snapshot", "initial content is not a saved snapshot")
        if artifact_root is None:
            reasons.append("snapshot-bytes-not-verified")
        else:
            verify_snapshot(artifact_root, profile["initialContent"])
    if not isinstance(profile.get("quality"), dict) or not profile["quality"]:
        reasons.append("quality-profile-unavailable")
    elif context is not None:
        quality = profile["quality"]
        bindings = {"framebufferWidth": "drawableWidth", "framebufferHeight": "drawableHeight",
                    "renderWidth": "internalWidth", "renderHeight": "internalHeight",
                    "effectiveRenderDistance": "renderDistance", "fpsLimitOption": "targetFps", "vsync": "vsync"}
        require(all(quality.get(key) == context[value] for key, value in bindings.items()),
                "profile quality differs from sampled source context")
        require(quality.get("nativeWindowPixelWidth") == quality.get("presentWidth") == context["drawableWidth"]
                and quality.get("nativeWindowPixelHeight") == quality.get("presentHeight") == context["drawableHeight"],
                "profile is not full native output quality")
    if not isinstance(profile.get("targetIntent"), dict) or not profile["targetIntent"]:
        reasons.append("target-intent-unavailable")
    else:
        target = profile["targetIntent"]
        require(target.get("authority") == "requested-options-not-system-deadline", "target intent claims unknown authority")
        if context is not None:
            require(target.get("fpsLimit") == context["targetFps"] and target.get("vsync") == context["vsync"],
                    "profile target intent differs from measured options")
    if not isinstance(profile.get("route"), dict) or not profile["route"]:
        reasons.append("replay-route-unavailable")
    else:
        route = profile["route"]
        require(route.get("warmupNs") == window["warmupNs"] and route.get("sampleNs") == window["endNs"] - window["startNs"],
                "profile/window duration mismatch")
        require(all(isinstance(route.get(key), str) and route[key] for key in
                    ("id", "inputAuthority", "samplePhase", "completion")), "incomplete replay route")



def verify_snapshot(artifact_root, content):
    root = Path(artifact_root).resolve()
    directory = content.get("snapshotDirectory")
    require(isinstance(directory, str) and directory in ("initial-world", "prewarmup-world"), "invalid snapshot directory")
    snapshot = root / directory
    require(snapshot.is_dir() and not snapshot.is_symlink(), "missing snapshot directory")
    manifest = json.loads((root / (directory + "-manifest.json")).read_text(), object_pairs_hook=unique_object)
    require(content.get("snapshotHashAlgorithm") == "sha256(sorted(relative-path + NUL + file-sha256 + LF))",
            "unknown snapshot hash algorithm")
    digest = hashlib.sha256()
    listed = set()
    previous = ""
    for item in manifest["files"]:
        relative = item["path"]
        require(isinstance(relative, str) and relative > previous and relative != "session.lock", "invalid snapshot manifest order/path")
        previous = relative
        path = snapshot / relative
        require(not Path(relative).is_absolute() and ".." not in Path(relative).parts and path.resolve().is_relative_to(snapshot),
                "snapshot path escapes content directory")
        require(path.is_file() and not any(part.is_symlink() for part in (path, *path.parents)), "snapshot symlink/missing file")
        require(path.stat().st_size == integer(item["bytes"]), "snapshot size mismatch")
        with path.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        require(actual == item["sha256"], "snapshot file digest mismatch")
        digest.update((relative + "\0" + actual + "\n").encode())
        listed.add(relative)
    actual_paths = {str(path.relative_to(snapshot)) for path in snapshot.rglob("*") if path.is_file()}
    require("level.dat" in listed and listed == actual_paths, "snapshot incomplete/unlisted content")
    require(digest.hexdigest() == content["snapshotSha256"] == manifest["identity"]["snapshotSha256"], "snapshot aggregate digest mismatch")


def self_test():
    head = "a" * 40
    fixture = {"schemaVersion": 1, "identity": {
        "build": {"sourceSha": head, "treeSha": "b" * 40, "dirty": False, "minecraftVersion": "26.3"},
        "requestedSourceSha": head, "mods": {"minecraft": "26.3"}, "backend": "Metal", "trialId": "fixture",
        "nativeSha256": "c" * 64, "javaArtifactSha256": "d" * 64},
        "scope": "Minecraft.renderFrame/render-thread/main-command-queue", "shutdownDrained": True,
        "validationStatus": "passed", "droppedFrames": 0, "unavailable": {"gpuFrameNs": "not command-buffer time"},
        "frames": [{"frameId": 1, "parentFrameId": 0, "cpuFrameNs": 100, "renderLevel": True, "ended": True, "failure": "",
                    "producerEntries": ["vanilla-terrain-layer-return"], "terrainBatchIndices": [0, 7],
                    "abi": {"metallum_draw": {"calls": 2, "inclusiveNs": 50, "exclusiveNs": 40, "failures": 0}},
                    "commandBuffers": [{"submissionId": 1, "nativeSubmitIndex": 0, "submitted": True,
                                        "presentationRequested": True, "nativePresentationId": 17,
                                        "presentationIdUnavailableReason": "",
                                        "completed": True, "success": True, "gpuServiceNs": 80,
                                        "drawableWaitNs": 0, "drawableWaitUnavailableReason": "",
                                        "gpuUnavailableReason": ""}]}]}
    assert verify(fixture, head, True)["cpuFrameNs"]["p99"] == 100
    legacy = copy.deepcopy(fixture)
    legacy["frames"][0]["abi"]["MTLRenderCommandEncoder_waitForFence"] = legacy["frames"][0]["abi"].pop("metallum_draw")
    assert verify(legacy, head)["renderThreadAbiCrossings"]["p50"] == 2
    unavailable = copy.deepcopy(fixture)
    unavailable["frames"][0]["commandBuffers"][0].update(gpuServiceNs=None, gpuUnavailableReason="gpu-timestamp-unavailable")
    assert verify(unavailable, head)["commandBufferGpuServiceNs"]["samples"] == 0
    deferred_present = copy.deepcopy(fixture)
    deferred_present["frames"][0]["commandBuffers"][0].update(
        nativePresentationId=None, presentationIdUnavailableReason="native-present-id-not-returned")
    verify(deferred_present, head)
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
        lambda x: x["frames"][0].update(terrainBatchIndices=[7, 7]),
        lambda x: x["frames"][0].update(terrainBatchIndices=[-1]),
        lambda x: x["frames"][0].update(terrainBatchIndices=list(range(65))),
        lambda x: x["frames"][0]["commandBuffers"][0].update(drawableWaitNs=-1),
        lambda x: x["frames"][0]["commandBuffers"][0].update(drawableWaitNs=None),
        lambda x: x["frames"][0]["abi"]["metallum_draw"].update(failures=1),
        lambda x: x["frames"][0]["abi"]["metallum_draw"].update(exclusiveNs=51),
        lambda x: x["frames"][0]["commandBuffers"][0].update(completed=False),
        lambda x: x["frames"][0]["commandBuffers"][0].update(success=False),
        lambda x: x["frames"][0]["commandBuffers"][0].update(gpuServiceNs=0),
        lambda x: x["frames"][0]["commandBuffers"][0].update(presentationRequested=False),
        lambda x: x["frames"][0]["commandBuffers"][0].update(nativePresentationId=0),
        lambda x: x["frames"][0]["commandBuffers"][0].update(presentationIdUnavailableReason="presented"),
    ]
    for mutate in mutations:
        broken = copy.deepcopy(fixture)
        mutate(broken)
        try:
            verify(broken, head)
        except (ValueError, KeyError, TypeError):
            continue
        raise AssertionError("invalid evidence accepted")
    windowed = copy.deepcopy(fixture)
    windowed["identity"]["instrumentationMode"] = "timing"
    windowed["window"] = {"epoch": 1, "clock": "java-System.nanoTime",
                          "membership": "root-source-start-half-open; nested-inherits-parent",
                          "armed": True, "closed": True, "startNs": 1000, "endNs": 2000, "warmupNs": 100,
                          "profile": {"instrumentationMode": "timing", "initialContent": {"snapshotSha256": "e" * 64, "kind": "flushed-world-snapshot"},
                                      "quality": {"fixture": True}, "targetIntent": {"fpsLimit": 60, "vsync": False, "authority": "requested-options-not-system-deadline"},
                                      "route": {"id": "fixture", "inputAuthority": "fixture", "samplePhase": "stationary",
                                                "completion": "fixture", "warmupNs": 100, "sampleNs": 1000}}}
    windowed["identity"]["nativeBuild"] = {"schemaVersion": 1,
        "build": copy.deepcopy(windowed["identity"]["build"]), "nativeSha256": "c" * 64}
    context = {"drawableWidth": 100, "drawableHeight": 100, "internalWidth": 100,
               "internalHeight": 100, "renderDistance": 8, "targetFps": 60, "vsync": False}
    windowed["frames"] = []
    # Timestamp order deliberately differs from source order. No cross-clock subtraction.
    for index, stamp in enumerate((90.04, 90.0, 90.02)):
        row = copy.deepcopy(fixture["frames"][0])
        row.update(frameId=index + 1, sourceStartNs=1000 + index * 400, epoch=1, abi={}, context=context.copy())
        row["commandBuffers"][0].update(submissionId=index + 1, nativePresentationId=index + 17,
                                        presentedTimeSeconds=stamp, presentedUnavailableReason="")
        windowed["frames"].append(row)
    windowed["window"]["profile"]["quality"] = {
        "framebufferWidth": 100, "framebufferHeight": 100, "renderWidth": 100, "renderHeight": 100,
        "nativeWindowPixelWidth": 100, "nativeWindowPixelHeight": 100, "presentWidth": 100, "presentHeight": 100,
        "effectiveRenderDistance": 8, "fpsLimitOption": 60, "vsync": False}
    stationary_gap = copy.deepcopy(windowed)
    stationary_gap["window"]["profile"]["profileId"] = "vanilla-stationary-60-v1"
    stationary_gap["frames"][1]["commandBuffers"] = []
    gap_delivery = verify(stationary_gap, head)["frameDelivery"]
    assert "stationary-source-presentation-request-coverage-incomplete" in gap_delivery["comparisonEligibility"]["reasons"]
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        (root / "initial-world").mkdir()
        (root / "initial-world" / "level.dat").write_bytes(b"synthetic-test-only")
        file_digest = hashlib.sha256(b"synthetic-test-only").hexdigest()
        aggregate = hashlib.sha256(("level.dat\0" + file_digest + "\n").encode()).hexdigest()
        content = windowed["window"]["profile"]["initialContent"]
        content.update(snapshotSha256=aggregate, snapshotDirectory="initial-world",
                       snapshotHashAlgorithm="sha256(sorted(relative-path + NUL + file-sha256 + LF))")
        manifest = {"identity": content, "files": [{"path": "level.dat", "sha256": file_digest, "bytes": 19}]}
        (root / "initial-world-manifest.json").write_text(json.dumps(manifest))
        delivery = verify(windowed, head, True, True, root)["frameDelivery"]
        (root / "initial-world" / "level.dat").write_bytes(b"changed")
        try:
            verify(windowed, head, artifact_root=root)
        except ValueError:
            pass
        else:
            raise AssertionError("changed snapshot accepted")
    assert delivery["sourceRootCount"] == 3 and delivery["sourceCountRateHz"] == 3_000_000
    assert abs(delivery["actualPresentEventSpanRateHz"] - 50) < .00001
    assert delivery["presentIntervalNs"]["p99.9"] is None
    assert delivery["physicalPerformanceAcceptance"] == "unverified"
    tied = copy.deepcopy(windowed)
    tied["frames"][0]["commandBuffers"][0].update(presentedTimeSeconds=90.0)
    ambiguous = verify(tied, head)["frameDelivery"]
    assert ambiguous["actualDrawableCallbackCount"] == 3 and ambiguous["coincidentPresentedTimestampCount"] == 1
    assert ambiguous["actualPresentationCount"] is None and ambiguous["actualPresentEventSpanRateHz"] is None
    assert ambiguous["presentIntervalNs"]["samples"] == 2  # Keep the zero interval; never deduplicate receipts.
    assert not ambiguous["comparisonEligibility"]["eligible"]
    try:
        verify(tied, head, require_comparable=True)
    except ValueError:
        pass
    else:
        raise AssertionError("coincident callbacks claimed distinct comparable display events")
    timing = verify(windowed, head)
    assert timing["renderThreadAbiCrossings"]["samples"] == 0
    assert timing["renderThreadAbiExclusiveNs"]["p50"] is None
    assert timing["renderThreadAbiCrossings"]["unavailableReason"] == "timing-mode-does-not-instrument-ABI"
    missing = copy.deepcopy(windowed)
    missing["frames"][1]["commandBuffers"][0].update(presentedTimeSeconds=None,
                                                    presentedUnavailableReason="callback-pending-at-export")
    incomplete = verify(missing, head)["frameDelivery"]
    assert not incomplete["comparisonEligibility"]["eligible"] and incomplete["actualPresentationCount"] == 2
    try:
        verify(missing, head, require_comparable=True)
    except ValueError:
        pass
    else:
        raise AssertionError("incomplete coverage claimed comparable")
    # Source end crossing is explicit cohort membership; full late callbacks remain included.
    late = copy.deepcopy(windowed)
    late["frames"][-1].update(cpuFrameNs=300)
    assert verify(late, head)["frameDelivery"]["sourceScopesCrossingEnd"] == 1
    nested = copy.deepcopy(windowed)
    nested["frames"][0].update(cpuFrameNs=600)
    nested["frames"][1].update(parentFrameId=1)
    nested_delivery = verify(nested, head)["frameDelivery"]
    assert nested_delivery["sourceRootCount"] == 2 and nested_delivery["actualPresentationCount"] == 3
    assert not nested_delivery["comparisonEligibility"]["eligible"]
    later_epoch = copy.deepcopy(windowed)
    later_epoch["window"]["epoch"] = 7
    for row in later_epoch["frames"]:
        row["epoch"] = 7
    assert verify(later_epoch, head)["frameDelivery"]["epoch"] == 7
    enough = copy.deepcopy(windowed)
    enough["frames"] = []
    for index in range(1001):
        row = copy.deepcopy(windowed["frames"][0])
        row.update(frameId=index + 1)
        row["commandBuffers"][0].update(submissionId=index + 1, nativePresentationId=index + 1,
                                        presentedTimeSeconds=100 + index * .02)
        enough["frames"].append(row)
    assert verify(enough, head)["frameDelivery"]["presentIntervalNs"]["p99.9"] is not None
    enough["frames"].pop()
    assert verify(enough, head)["frameDelivery"]["presentIntervalNs"]["p99.9"] is None
    mutations = [
        lambda x: x["window"].update(closed=False),
        lambda x: x["identity"]["nativeBuild"]["build"].update(treeSha="f" * 40),
        lambda x: x["identity"]["nativeBuild"].update(nativeSha256="f" * 64),
        lambda x: x["window"].update(epoch=0),
        lambda x: x["frames"][1].update(epoch=2),
        lambda x: x["frames"][0].update(sourceStartNs=999),
        lambda x: x["frames"][-1].update(sourceStartNs=2000),
        lambda x: x["frames"][1]["context"].update(internalWidth=90),
        lambda x: x["frames"][1]["context"].update(targetFps=30),
        lambda x: x["frames"][0]["commandBuffers"][0].update(nativePresentationId=18),
        lambda x: x["frames"][0].update(abi={"call": {"calls": 1, "inclusiveNs": 1, "exclusiveNs": 1, "failures": 0}}),
        lambda x: x["identity"]["mods"].update(iris="fixture"),
    ]
    for mutate in mutations:
        broken = copy.deepcopy(windowed)
        mutate(broken)
        try:
            verify(broken, head)
        except (ValueError, KeyError, TypeError):
            continue
        raise AssertionError("invalid explicit window accepted")
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
    parser.add_argument("--require-comparable", action="store_true")
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
            print(json.dumps(verify(report, args.expected_head, args.require_packaged, args.require_comparable, args.report.parent), indent=2))
        except (ValueError, KeyError, TypeError, OSError) as error:
            print(json.dumps({"status": "rejected-frame-evidence", "reason": str(error)}))
            raise SystemExit(1)
