#!/usr/bin/env python3
"""Identity and protocol checks for the existing production gameplay driver.

This is a trial manifest, not a second frame recorder or performance verdict.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
from pathlib import Path
import re
import zipfile

WORKLOADS = ("P0", "T0", "C0", "G0", "I0", "I1", "X0")
MODES = ("off", "timing", "diagnostic")
BACKENDS = ("metal3", "metal4")
PRODUCERS = ("vanilla", "sodium", "iris")


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise ValueError(reason)


def _unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def parse_json(data: str | bytes):
    return json.loads(data, object_pairs_hook=_unique,
                      parse_constant=lambda value: require(False, f"non-finite JSON: {value}"))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda: stream.read(1 << 20), b""):
            digest.update(data)
    return digest.hexdigest()


def canonical_hash(value) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     allow_nan=False).encode()).hexdigest()


def atomic_json(path: Path, value) -> None:
    # A leftover partial is evidence of an interrupted writer; never silently reuse it.
    data = (json.dumps(value, indent=2, allow_nan=False) + "\n").encode()
    temporary = path.with_name(path.name + ".partial")
    with temporary.open("xb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def digest_string(value, length=64):
    return isinstance(value, str) and re.fullmatch("[0-9a-f]{%d}" % length, value) is not None


def packaged_identity(jar: Path) -> dict:
    require(jar.is_file() and not jar.is_symlink(), "JAR must be an immutable regular file")
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "JAR has duplicate entries")
        build = parse_json(archive.read("metallum-build-identity.json"))
        native_build = parse_json(archive.read("natives/macos/libmetallum-build-identity.json"))
        with archive.open("natives/macos/libmetallum.dylib") as stream:
            native_hash = hashlib.file_digest(stream, "sha256").hexdigest()
    require(build.get("dirty") is False, "dirty/unknown JAR build")
    require(digest_string(build.get("sourceSha"), 40) and digest_string(build.get("treeSha"), 40),
            "missing exact source/tree identity")
    require(native_build.get("schemaVersion") == 1 and native_build.get("build") == build,
            "native and Java source identities differ")
    require(native_build.get("nativeSha256") == native_hash, "packaged native digest mismatch")
    require(build.get("minecraftVersion") == "26.3", "this workload driver is versioned for Minecraft 26.3")
    return {"build": build, "javaArtifactSha256": sha256(jar), "packagedNativeSha256": native_hash}


def snapshot_identity(snapshot: Path) -> dict:
    """Same digest contract as WorldSnapshot.java, with all path components checked."""
    require(snapshot.is_dir() and not snapshot.is_symlink(), "snapshot directory missing/symlinked")
    snapshot = snapshot.resolve(strict=True)
    manifest_path = snapshot.with_name(snapshot.name + "-manifest.json")
    require(manifest_path.is_file() and not manifest_path.is_symlink(), "snapshot manifest missing/symlinked")
    manifest = parse_json(manifest_path.read_bytes())
    rows = manifest["files"]
    require(isinstance(rows, list) and bool(rows), "empty world snapshot")
    digest, previous, listed = hashlib.sha256(), "", set()
    for row in rows:
        name = row["path"]
        require(isinstance(name, str) and name > previous and name != "session.lock"
                and not Path(name).is_absolute() and all(p not in ("", ".", "..") for p in name.split("/"))
                and "\\" not in name and "\x00" not in name, "invalid world path/order")
        current = snapshot
        for part in name.split("/"):
            current /= part
            require(not current.is_symlink(), "world snapshot contains a symlink")
        require(current.is_file() and type(row["bytes"]) is int and row["bytes"] >= 0,
                "invalid world file")
        actual = sha256(current)
        require(actual == row["sha256"] and current.stat().st_size == row["bytes"], "world file digest/size mismatch: " + name)
        digest.update((name + "\0" + actual + "\n").encode())
        previous = name
        listed.add(name)
    actual_files = set()
    for path in snapshot.rglob("*"):
        require(not path.is_symlink(), "world snapshot contains a symlink")
        if path.is_file():
            actual_files.add(path.relative_to(snapshot).as_posix())
        else:
            require(path.is_dir(), "world snapshot contains a special file")
    require(actual_files == listed and "level.dat" in listed, "world snapshot contains missing/unlisted files")
    require(digest.hexdigest() == manifest["identity"]["snapshotSha256"], "world snapshot identity mismatch")
    return {"snapshotSha256": digest.hexdigest(), "manifestSha256": sha256(manifest_path),
            "fileCount": len(listed), "bytes": sum(row["bytes"] for row in rows)}


def shader_identity(path: Path | None, expected: str | None) -> dict:
    require(path is not None and expected is not None and digest_string(expected),
            "Iris workload needs a user-provided shader ZIP and SHA-256")
    require(path.is_file() and not path.is_symlink(), "shader pack must be a regular ZIP file")
    require(sha256(path) == expected, "shader pack digest mismatch")
    require(path.stat().st_size <= 512 * 1024 * 1024, "shader ZIP exceeds the declared input budget")
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(set(names)) == len(names), "shader ZIP contains duplicate names")
        total = 0
        for entry in archive.infolist():
            name = entry.filename
            require(not Path(name).is_absolute() and ".." not in Path(name).parts and "\\" not in name
                    and "\x00" not in name and (entry.external_attr >> 16) & 0o170000 != 0o120000,
                    "unsafe shader ZIP entry")
            total += entry.file_size
        require(total <= 2 * 1024**3, "expanded shader pack exceeds input budget")
        require(any(name.startswith("shaders/") and name.endswith((".vsh", ".fsh", ".csh")) for name in names),
                "shader ZIP must contain the pack's shaders directory at its root")
    return {"sha256": expected, "bytes": path.stat().st_size, "stagedName": expected + ".zip",
            "authority": "user-provided-content; activation requires a separate runtime receipt"}


def trial_order(protocol: str, blocks: int) -> list[dict]:
    require(type(blocks) is int and 1 <= blocks <= 64, "blocks must be in [1,64]")
    order = {"aa": "AA", "abba": "ABBA", "baab": "BAAB", "observer": "ABBA"}.get(protocol)
    require(order is not None, "unknown trial protocol")
    return [{"ordinal": block * len(order) + slot + 1, "block": block + 1, "slot": slot + 1, "variant": letter}
            for block in range(blocks) for slot, letter in enumerate(order)]


def validate_pair(protocol: str, variants: dict) -> None:
    require(protocol in ("aa", "abba", "baab", "observer"), "unknown trial protocol")
    require(set(variants) == {"A", "B"}, "exactly two declared variants are required")
    for variant in variants.values():
        require(variant["observer"] in MODES and variant["backend"] in BACKENDS
                and variant["producer"] in PRODUCERS, "unsupported trial variant")
    if protocol == "aa":
        require(variants["A"] == variants["B"], "A/A must use the identical binary, instrumentation and policy")
    elif protocol == "observer":
        a, b = dict(variants["A"]), dict(variants["B"])
        require(a.pop("observer") == "off" and b.pop("observer") in ("timing", "diagnostic"),
                "observer comparison must be OFF versus timing/diagnostic")
        require(a == b, "observer comparison changed the implementation or policy")
    else:
        require(variants["A"]["observer"] == variants["B"]["observer"], "A/B instrumentation modes differ")
        require(variants["A"]["producer"] == variants["B"]["producer"], "A/B producer semantics differ")


def verify_loaded(expected: dict, loaded: dict, frame: dict | None) -> None:
    for key in ("build", "javaArtifactSha256", "packagedNativeSha256"):
        require(loaded.get(key) == expected[key], "loaded artifact identity mismatch: " + key)
    if frame is not None:
        identity = frame["identity"]
        require(identity.get("build") == expected["build"] and identity.get("javaArtifactSha256") == expected["javaArtifactSha256"]
                and identity.get("nativeSha256") == expected["packagedNativeSha256"], "loaded native/frame artifact identity mismatch")


def finite_seconds(value: float, minimum: float, maximum: float, name: str) -> float:
    require(math.isfinite(value) and minimum <= value <= maximum, f"{name} must be in [{minimum},{maximum}]")
    return value


def validate_gameplay(gameplay: dict, specification: dict) -> dict:
    """Check a completed versioned input route, not a visual/performance verdict."""
    workload = specification["workloadId"]
    require(workload in WORKLOADS, "unknown workload")
    require(gameplay.get("status") == "completed", "workload did not complete")
    receipt, profile = gameplay["workload"], gameplay["frameEvidenceProfile"]
    source = gameplay["sourceSampleWindow"]
    require(receipt.get("workloadId") == profile.get("workloadId") == workload
            and receipt.get("protocolVersion") == profile.get("protocolVersion") == 1, "workload identity mismatch")
    require(profile.get("workloadSha256") == canonical_hash(specification), "executed workload digest mismatch")
    require(receipt.get("completed") is True and source.get("complete") is True, "incomplete source window/route")
    start, end = source["startNs"], source["endNs"]
    require(type(start) is int and type(end) is int and end - start == specification["sampleNs"], "source window duration mismatch")
    require(receipt.get("sourceSampleStartNs") == start and receipt.get("sourceSampleEndNs") == end,
            "action/source windows differ")
    require(source.get("clock") == "System.nanoTime" and source.get("membership") == "half-open [startNs,endNs)",
            "source clock/boundary mismatch")
    require(type(source.get("count")) is int and source["count"] >= 2 and source.get("intervalCount") == source["count"] - 1,
            "insufficient/inconsistent source events")
    require(source.get("invalidTimestamps") == 0 and math.isclose(source["fps"], source["count"] * 1e9 / (end - start), rel_tol=1e-12),
            "invalid source count rate")
    summary = gameplay["sourceFrames"]
    require(all(summary.get(key) == 0 for key in ("invalidSettingsFrames", "throttledFrames", "droppedSamples")),
            "quality/cadence/instrumentation failure")
    require(all(summary.get(key) == source[key] for key in ("startNs", "endNs", "count", "intervalCount", "fps")),
            "final source sample differs from the route sample")
    require(profile["route"].get("warmupNs") == specification["warmupNs"]
            and profile["route"].get("sampleNs") == specification["sampleNs"], "executed warmup/sample changed")
    require(profile["targetIntent"] == {"fpsLimit": specification["targetFps"], "vsync": True,
                                        "authority": "requested-options-not-system-deadline"}, "cadence intent changed")
    require(gameplay["settings"] == gameplay["finalSettings"] == profile["quality"], "quality settings changed")
    settings = gameplay["settings"]
    require(settings.get("effectiveRenderDistance") == settings.get("renderDistance") == 32, "render distance changed")
    for axis, keys in (("width", ("nativeWindowPixelWidth", "presentWidth", "framebufferWidth", "renderWidth")),
                       ("height", ("nativeWindowPixelHeight", "presentHeight", "framebufferHeight", "renderHeight"))):
        require(all(settings.get(key) == specification["output"][axis] for key in keys), "output/internal resolution mismatch")
    require(gameplay.get("backendRequested") == specification["backend"]
            and gameplay.get("metal4MainRendererActive") is (specification["backend"] == "metal4"), "Metal lowering did not activate")
    producer = specification["producer"]
    for state in (receipt["producerBefore"], receipt["producerAfter"]):
        require(state.get("producer") == producer and state.get("sodiumInstalled") is (producer != "vanilla")
                and state.get("irisInstalled") is (producer == "iris"), "producer activation changed")
        if producer == "iris":
            require(type(state.get("generation")) is int and state["generation"] >= 0
                    and state.get("shaderPack") == specification["shaderPack"]["stagedName"], "Iris pack did not actually activate")
    if producer == "iris":
        before, after = receipt["producerBefore"]["generation"], receipt["producerAfter"]["generation"]
        require(after > before if workload == "X0" else after == before, "Iris generation/reload contract failed")
    actions = receipt["actions"]
    expected = [f"flight-leg-{i}" for i in range(6)] if workload == "T0" else (
        ["windowed-resize-half-output", "resource-reload"] + (["iris-reload"] if producer == "iris" else [])
        + ["restore-fullscreen-output"] if workload == "X0" else ["fixed-view"])
    require([row.get("id") for row in actions] == expected, "input action sequence did not complete")
    previous = start
    for action in actions:
        stamp = action["sourceClockNs"]
        require(type(stamp) is int and previous <= stamp < end, "warmup consumed an action or action exceeded sample")
        previous = stamp
    require(type(receipt.get("completionNs")) is int and receipt["completionNs"] >= end, "route returned before window end")
    require(type(receipt.get("serverTickAtCompletion")) is int and type(receipt.get("serverTickAtActionsStart")) is int
            and receipt["serverTickAtCompletion"] > receipt["serverTickAtActionsStart"], "simulation made no recorded progress")
    if workload == "T0":
        require(receipt.get("horizontalDisplacementBlocks", 0) > 100, "T0 input did not traverse terrain")
    if workload == "X0":
        require(0 < receipt.get("windowedPixelWidth", 0) < specification["output"]["width"],
                "X0 only changed a virtual framebuffer, not the SDL window")
    initial = profile["initialContent"]
    if specification["initialWorld"] is not None:
        require(initial.get("snapshotSha256") == specification["initialWorld"]["snapshotSha256"], "trial used a different initial world")
    return {"status": "valid-observation", "scope": "source/input/quality/producer contracts, not visual or pacing acceptance",
            "sourceSampleWindow": source, "serverTicks": receipt["serverTickAtCompletion"] - receipt["serverTickAtActionsStart"],
            "transitionWindow": workload == "X0", "settingsSha256": canonical_hash(settings),
            "physicalPerformanceAcceptance": "physical-validation-required", "productPromotable": False}


def artifact_inventory(directory: Path) -> list[dict]:
    """Hash raw receipts/logs/segments. World bytes have their separate content manifest."""
    skip = {"initial-world", "prewarmup-world", "gameplay.trace"}
    result = []
    for path in sorted(directory.rglob("*")):
        relative = path.relative_to(directory)
        if any(part in skip for part in relative.parts) or path.name in ("trial-manifest.json", "trial-manifest.json.partial"):
            continue
        if relative.parts[0] == "client-instance" and (len(relative.parts) < 2 or relative.parts[1] not in ("logs", "crash-reports")):
            continue
        require(not path.is_symlink(), "artifact directory contains a symlink")
        if path.is_file():
            result.append({"path": relative.as_posix(), "sha256": sha256(path), "bytes": path.stat().st_size})
    return result


def verify_inventory(directory: Path, records: list[dict]) -> None:
    require(isinstance(records, list), "missing raw artifact inventory")
    for record in records:
        name = record["path"]
        require(isinstance(name, str) and not Path(name).is_absolute() and all(x not in ("", ".", "..") for x in name.split("/"))
                and "\\" not in name and "\x00" not in name, "unsafe artifact path")
    require(artifact_inventory(directory) == records, "raw artifacts changed, disappeared, or were added after the trial")


def runner_lock(root: Path):
    """One attended physical campaign at a time. Child trials share the locked descriptor."""
    import fcntl
    lock_dir = root / "build/agent-evidence"
    lock_dir.mkdir(parents=True, exist_ok=True)
    path = lock_dir / "physical-runner.lock"
    inherited = os.environ.get("METALLUM_FRAME_LOCK_FD")
    if inherited is not None:
        require(inherited.isdigit(), "invalid inherited physical lock")
        descriptor = int(inherited)
        actual, expected = os.fstat(descriptor), path.stat()
        require((actual.st_dev, actual.st_ino) == (expected.st_dev, expected.st_ino), "inherited physical lock belongs to another checkout")
        stream = os.fdopen(os.dup(descriptor), "a")
    else:
        require(not path.is_symlink(), "physical lock cannot be a symlink")
        stream = path.open("a")
    try:
        fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        return stream
    except BaseException:
        stream.close()
        raise
