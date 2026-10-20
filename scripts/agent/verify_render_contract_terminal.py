#!/usr/bin/env python3
"""Validate persisted terminal capture evidence independently of live snapshots."""
from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
import re
import tempfile


def integer(value: object) -> bool:
    return type(value) is int and value >= 0


def check(manifest: dict, results: dict, source: str, captures: int) -> dict:
    def require(condition: bool, reason: str) -> None:
        if not condition:
            raise ValueError(reason)

    require(re.fullmatch(r"[0-9a-f]{40}", source or "") is not None, "expected source is not an exact SHA")
    require(type(captures) is int and captures > 0, "expected capture count must be positive")
    for label, document in (("manifest", manifest), ("results", results)):
        require(isinstance(document, dict), f"{label} must be an object")
        require(type(document.get("schemaVersion")) is int and document["schemaVersion"] == 1,
                f"{label} schema is unsupported")
        require(document.get("gitCommit") == source, f"{label} belongs to another source")
        require(document.get("status") == "passed", f"{label} is not terminal passed")
    require(isinstance(manifest.get("runId"), str) and bool(manifest["runId"])
            and manifest["runId"] == results.get("runId"), "capture and trace run identities differ")
    require(manifest.get("manifestComplete") is True, "manifest is not complete after close")
    require(manifest.get("producerBudgetExceeded") is False, "producer budget proof is missing or failed")
    require(manifest.get("tracePayloadOmitted", False) is False, "trace payload was omitted")
    for key in ("droppedEvents", "forcedClosedPassCount", "invalidPassReferenceCount"):
        require(type(manifest.get(key)) is int and manifest[key] == 0, f"manifest {key} is missing or nonzero")
    for count, array in (("frameCount", "frames"), ("passCount", "passes"), ("resourceCount", "resources")):
        require(integer(manifest.get(count)) and manifest[count] > 0, f"manifest {count} is invalid")
        require(isinstance(manifest.get(array), list) and len(manifest[array]) == manifest[count],
                f"manifest {count}/{array} differ")
    frames = manifest["frames"]
    require(all(integer(frame) for frame in frames) and len(set(frames)) == len(frames), "ambiguous frame identities")
    require(manifest.get("openPasses") == [], "open pass payload is missing or nonempty")
    require(integer(manifest.get("producerCount")), "producer count is missing")
    limits = manifest.get("limits", {})
    require(isinstance(limits, dict), "manifest limits are malformed")
    require(integer(limits.get("maxProducers")) and manifest["producerCount"] <= limits["maxProducers"],
            "producer count exceeds its declared budget")
    storage = manifest.get("storageBudget", {})
    require(isinstance(storage, dict), "manifest storage proof is malformed")
    require(storage.get("exceeded") is False, "manifest storage budget proof is missing or failed")
    require(integer(storage.get("artifactBytes")) and integer(storage.get("maxBytes"))
            and storage["artifactBytes"] <= storage["maxBytes"], "manifest storage counters are invalid")
    for key in ("requestedCaptures", "completedCaptures"):
        require(type(results.get(key)) is int and results[key] == captures, f"results {key} differs from protocol")
    for key in ("failedCaptures", "pendingCaptures", "droppedCaptures", "lateCompletions"):
        require(type(results.get(key)) is int and results[key] == 0, f"results {key} is missing or nonzero")
    require(results.get("storageBudgetExceeded") is False, "capture storage budget proof is missing or failed")
    require(integer(results.get("artifactBytes")) and integer(results.get("maxArtifactBytes"))
            and results["artifactBytes"] <= results["maxArtifactBytes"], "capture storage counters are invalid")
    require(integer(results.get("capturedBytes")) and results["capturedBytes"] > 0, "no captured payload")
    entries = results.get("captures")
    require(isinstance(entries, list) and len(entries) == captures, "capture entries differ from completed count")
    identities = set()
    for capture in entries:
        require(isinstance(capture, dict) and capture.get("status") == "passed", "a capture is not passed")
        require(capture.get("gitCommit") == source and capture.get("runId") == manifest["runId"], "capture source/run mismatch")
        require(integer(capture.get("frameId")) and capture["frameId"] in frames, "capture has no recorded source frame")
        require(isinstance(capture.get("semanticPassId"), str) and bool(capture["semanticPassId"])
                and isinstance(capture.get("capturePoint"), str) and bool(capture["capturePoint"])
                and type(capture.get("producerIndex")) is int and capture["producerIndex"] >= -1,
                "capture point identity is incomplete")
        identity = (capture["frameId"], capture["semanticPassId"], capture["capturePoint"], capture["producerIndex"])
        require(identity not in identities, "duplicate capture point")
        identities.add(identity)
        require(not capture.get("resourceValidationErrors"), "capture resource validation failed")
        resources = capture.get("resources")
        require(isinstance(resources, list) and bool(resources), "capture has no resources")
        for resource in resources:
            require(isinstance(resource, dict) and resource.get("status") != "failed" and not resource.get("error"),
                    "capture resource failed")
            expectations = resource.get("expectations", [])
            require(isinstance(expectations, list), "resource expectations are malformed")
            require(all(isinstance(item, dict) and isinstance(item.get("result"), dict)
                        and item["result"].get("passed") is True for item in expectations), "resource expectation failed")
    return {"source": source, "runId": manifest["runId"], "status": "passed", "manifestComplete": True,
            "captures": captures, "frames": manifest["frameCount"], "passes": manifest["passCount"],
            "producers": manifest["producerCount"]}


def verify(root: Path, source: str, captures: int) -> dict:
    if (root / "pass-manifest.json.partial").exists():
        raise ValueError("unpublished manifest checkpoint remains")
    manifest = json.loads((root / "pass-manifest.json").read_text(encoding="utf-8"))
    results = json.loads((root / "results.json").read_text(encoding="utf-8"))
    return check(manifest, results, source, captures)


def self_test() -> None:
    source = "a" * 40
    manifest = {"schemaVersion": 1, "runId": "fixture", "gitCommit": source, "status": "passed",
                "manifestComplete": True, "producerBudgetExceeded": False, "droppedEvents": 0,
                "forcedClosedPassCount": 0, "invalidPassReferenceCount": 0, "frameCount": 1, "frames": [7],
                "passCount": 1, "passes": [{}], "resourceCount": 1, "resources": [{}], "openPasses": [],
                "producerCount": 3, "limits": {"maxProducers": 3},
                "storageBudget": {"exceeded": False, "artifactBytes": 1024, "maxBytes": 4096}}
    results = {"schemaVersion": 1, "runId": "fixture", "gitCommit": source, "status": "passed",
               "requestedCaptures": 1, "completedCaptures": 1, "failedCaptures": 0, "pendingCaptures": 0,
               "droppedCaptures": 0, "lateCompletions": 0, "storageBudgetExceeded": False,
               "artifactBytes": 1024, "maxArtifactBytes": 4096, "capturedBytes": 4,
               "captures": [{"status": "passed", "gitCommit": source, "runId": "fixture", "frameId": 7,
                             "semanticPassId": "present", "capturePoint": "FINAL_DRAWABLE", "producerIndex": -1,
                             "resources": [{"expectations": [{"result": {"passed": True}}]}]}]}
    assert check(manifest, results, source, 1)["manifestComplete"]
    mutations = [(0, "status", "active"), (0, "manifestComplete", False), (0, "gitCommit", "b" * 40),
                 (0, "forcedClosedPassCount", 1), (0, "invalidPassReferenceCount", 1), (0, "droppedEvents", 1),
                 (0, "producerBudgetExceeded", True), (0, "tracePayloadOmitted", True), (0, "openPasses", [{}]),
                 (0, "frames", [7, 7]), (0, "producerCount", 4), (1, "runId", "another"),
                 (1, "status", "active"), (1, "completedCaptures", True), (1, "pendingCaptures", 1),
                 (1, "lateCompletions", 1), (1, "droppedCaptures", 1), (1, "failedCaptures", 1),
                 (1, "storageBudgetExceeded", True), (1, "captures", []), (1, "capturedBytes", 0)]
    for index, key, value in mutations:
        pair = [copy.deepcopy(manifest), copy.deepcopy(results)]
        pair[index][key] = value
        try:
            check(*pair, source, 1)
        except ValueError:
            continue
        raise AssertionError(f"invalid terminal fixture accepted: {index}/{key}")
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        for name, document in (("pass-manifest.json", manifest), ("results.json", results)):
            (root / name).write_text(json.dumps(document), encoding="utf-8")
        assert verify(root, source, 1)["captures"] == 1
        (root / "pass-manifest.json.partial").write_text("unpublished", encoding="utf-8")
        try:
            verify(root, source, 1)
        except ValueError:
            pass
        else:
            raise AssertionError("unpublished checkpoint accepted")
    print("render-contract terminal self-test: PASS (positive and 22 negative controls)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--root", type=Path)
    parser.add_argument("--source")
    parser.add_argument("--captures", type=int)
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.root is None or args.source is None or args.captures is None:
        parser.error("--root, --source and --captures are required")
    try:
        result = verify(args.root, args.source, args.captures)
    except (OSError, ValueError) as failure:
        print(json.dumps({"status": "failed", "reason": str(failure)}))
        return 1
    print("verified terminal render contract: " + json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
