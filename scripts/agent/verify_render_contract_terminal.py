#!/usr/bin/env python3
"""Validate persisted terminal capture evidence independently of live snapshots."""
from __future__ import annotations

import argparse
import copy
import gzip
import hashlib
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
        require(document.get("status") == "passed", f"{label} is not terminal passed: status={document.get('status')!r}, complete={document.get('manifestComplete')!r}, dropped={document.get('droppedEvents')!r}, forced={document.get('forcedClosedPassCount')!r}, invalid={document.get('invalidPassReferenceCount')!r}, reason={document.get('manifestFailureReason')!r}")
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
    if any(root.glob("pass-manifest*.partial")):
        raise ValueError("unpublished manifest checkpoint remains")
    index_path = root / "pass-manifest.json"
    index = json.loads(index_path.read_text(encoding="utf-8"))
    if not isinstance(index, dict):
        raise ValueError("manifest must be an object")
    manifest = index
    representation = index.get("schemaVersion")
    if representation == 2:
        if index.get("payloadEncoding") != "gzip-json-v1" or index.get("tracePayloadOmitted") is not False:
            raise ValueError("compressed trace encoding is missing or incomplete")
        payload = index.get("payload")
        if not isinstance(payload, dict) or payload.get("path") != "pass-manifest.payload.json.gz":
            raise ValueError("unsafe or missing compressed trace path")
        path = root / payload["path"]
        if path.is_symlink() or path.resolve().parent != root.resolve():
            raise ValueError("compressed trace escapes its root")
        limits = index.get("limits", {})
        if not isinstance(limits, dict):
            raise ValueError("compressed trace limits are missing")
        physical = limits.get("maxManifestBytes")
        raw_limit = limits.get("maxUncompressedManifestBytes")
        size, raw_size = payload.get("bytes"), payload.get("uncompressedBytes")
        if not all(integer(value) and value > 0 for value in (physical, raw_limit, size, raw_size)):
            raise ValueError("compressed trace byte proof is invalid")
        if raw_limit > 1024 * 1024 * 1024 or raw_size > raw_limit:
            raise ValueError("compressed trace decoding limit is exceeded")
        if path.stat().st_size != size or index_path.stat().st_size + size > physical:
            raise ValueError("index and compressed payload exceed their unchanged manifest byte budget")
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if payload.get("sha256") != digest:
            raise ValueError("compressed trace digest mismatch")
        # Bound inflation before JSON parsing; retain neither the compressed
        # file nor a second document-sized byte buffer in memory.
        with tempfile.TemporaryFile(mode="w+b") as decoded:
            total = 0
            with gzip.open(path, "rb") as stream:
                while chunk := stream.read(64 * 1024):
                    total += len(chunk)
                    if total > raw_size:
                        raise ValueError("compressed trace expands beyond its declared bound")
                    decoded.write(chunk)
            if total != raw_size:
                raise ValueError("compressed trace byte count mismatch")
            decoded.seek(0)
            manifest = json.load(decoded)
        if not isinstance(manifest, dict) or manifest.get("schemaVersion") != 1:
            raise ValueError("compressed trace payload schema is unsupported")
        for key in ("runId", "gitCommit", "status", "manifestComplete", "frameCount", "passCount", "resourceCount",
                    "producerCount", "droppedEvents", "forcedClosedPassCount", "invalidPassReferenceCount",
                    "producerBudgetExceeded"):
            if type(manifest.get(key)) is not type(index.get(key)) or manifest.get(key) != index.get(key):
                raise ValueError(f"compressed trace header mismatch: {key}")
        for key in ("maxFrames", "maxPasses", "maxProducers", "maxManifestBytes"):
            if manifest.get("limits", {}).get(key) != limits.get(key):
                raise ValueError(f"compressed trace limit mismatch: {key}")
        if not isinstance(index.get("storageBudget"), dict) or index["storageBudget"].get("exceeded") is not False:
            raise ValueError("compressed trace storage budget is missing or failed")
        # The payload snapshot precedes codec publication. Check both storage
        # proofs; the index contains the later physical accounting snapshot.
        original_storage = manifest.get("storageBudget", {})
        if not isinstance(original_storage, dict) or original_storage.get("exceeded") is not False:
            raise ValueError("payload storage budget is missing or failed")
        if not integer(original_storage.get("artifactBytes")) or not integer(original_storage.get("maxBytes")) \
                or original_storage["artifactBytes"] > original_storage["maxBytes"]:
            raise ValueError("payload storage counters are invalid")
        manifest["storageBudget"] = index["storageBudget"]
    elif representation != 1:
        raise ValueError("unsupported trace representation")
    results = json.loads((root / "results.json").read_text(encoding="utf-8"))
    verdict = check(manifest, results, source, captures)
    verdict["sourceSchemaVersion"] = representation
    if representation == 2:
        verdict.update(payloadSha256=payload["sha256"], payloadBytes=size,
                       uncompressedBytes=raw_size, manifestBytes=size + index_path.stat().st_size)
    return verdict


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
    compressed_negatives = 0
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        payload_manifest = copy.deepcopy(manifest)
        payload_manifest["limits"].update(maxFrames=16, maxPasses=32, maxManifestBytes=8192)
        encoded = json.dumps(payload_manifest).encode()
        compressed = gzip.compress(encoded)
        index = {key: copy.deepcopy(value) for key, value in payload_manifest.items()
                 if key not in ("frames", "passes", "resources", "openPasses")}
        index.update(schemaVersion=2, payloadEncoding="gzip-json-v1", tracePayloadOmitted=False,
                     payload={"path": "pass-manifest.payload.json.gz", "sha256": hashlib.sha256(compressed).hexdigest(),
                              "bytes": len(compressed), "uncompressedBytes": len(encoded)})
        index["limits"]["maxUncompressedManifestBytes"] = 1024 * 1024
        def publish(candidate, data=compressed):
            (root / "pass-manifest.json").write_text(json.dumps(candidate), encoding="utf-8")
            (root / "pass-manifest.payload.json.gz").write_bytes(data)
            (root / "results.json").write_text(json.dumps(results), encoding="utf-8")
        publish(index)
        assert verify(root, source, 1)["sourceSchemaVersion"] == 2
        changes = [("payload", "path", "../outside.gz"), ("payload", "sha256", "0" * 64),
                   ("payload", "bytes", len(compressed) + 1), ("payload", "uncompressedBytes", len(encoded) - 1),
                   ("payload", "uncompressedBytes", len(encoded) + 1),
                   ("limits", "maxManifestBytes", len(compressed)), ("limits", "maxUncompressedManifestBytes", len(encoded) - 1),
                   ("limits", "maxUncompressedManifestBytes", 2 * 1024 * 1024 * 1024),
                   ("storageBudget", "exceeded", True), (None, "producerCount", 4),
                   (None, "passCount", True), (None, "gitCommit", "b" * 40),
                   (None, "payloadEncoding", "unknown"), (None, "tracePayloadOmitted", True)]
        for section, key, value in changes:
            bad = copy.deepcopy(index)
            (bad if section is None else bad[section])[key] = value
            publish(bad)
            try:
                verify(root, source, 1)
            except (OSError, ValueError, EOFError):
                compressed_negatives += 1
            else:
                raise AssertionError(f"invalid compressed trace accepted: {section}/{key}")
        for data, declared_raw in ((compressed[:-4], len(encoded)), (compressed[:-8] + bytes(8), len(encoded)),
                                   (gzip.compress(encoded[:-1]), len(encoded) - 1),
                                   (gzip.compress(encoded + b" {}"), len(encoded) + 3)):
            bad = copy.deepcopy(index)
            bad["payload"].update(bytes=len(data), uncompressedBytes=declared_raw, sha256=hashlib.sha256(data).hexdigest())
            publish(bad, data)
            try:
                verify(root, source, 1)
            except (OSError, ValueError, EOFError):
                compressed_negatives += 1
            else:
                raise AssertionError("damaged compressed trace accepted")
        publish(index)
        (root / "pass-manifest.payload.json.gz").unlink()
        try:
            verify(root, source, 1)
        except OSError:
            compressed_negatives += 1
        else:
            raise AssertionError("missing compressed trace accepted")
    print(f"render-contract terminal self-test: PASS (legacy positive/22 negatives; compressed positive/{compressed_negatives} negatives)")



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
    except (OSError, ValueError, EOFError) as failure:
        print(json.dumps({"status": "failed", "reason": str(failure)}))
        return 1
    print("verified terminal render contract: " + json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
