#!/usr/bin/env python3
"""Fail-closed P1 V1/I0/I1 physical performance matrix validator."""
from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path
from typing import Any

PROFILES = ("V1", "I0", "I1")
COMMON_KEYS = (
    "candidate_sha",
    "production_jar_sha256",
    "native_dylib_sha256",
    "world_sha256",
    "world_scenario_id",
    "resolution",
    "ui_scale",
    "render_distance",
    "camera_pose",
    "camera_script_sha256",
    "minecraft_version",
    "sodium_version",
    "macos_version",
    "java_version",
)
SHADER_KEYS = (
    "shader_pack_name",
    "shader_pack_version",
    "shader_pack_sha256",
    "shader_options_sha256",
    "iris_version",
)


def load_object(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"could not read {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise ValueError(f"{path} is not a JSON object")
    return data


def evaluate(root: Path, head: str, jar_sha: str, dylib_sha: str) -> tuple[dict[str, Any], int]:
    identities: dict[str, dict[str, Any]] = {}
    decisions: dict[str, dict[str, Any]] = {}
    errors: list[str] = []

    for profile in PROFILES:
        profile_root = root / "profiles" / profile
        try:
            environment = load_object(profile_root / "environment.json")
            decision = load_object(profile_root / "decision.json")
        except ValueError as exc:
            errors.append(f"{profile}: {exc}")
            continue

        identity = environment.get("identity")
        if not isinstance(identity, dict):
            errors.append(f"{profile}: missing identity object")
            continue
        if identity.get("profile_id") != profile:
            errors.append(f"{profile}: identity profile_id={identity.get('profile_id')!r}")
        if identity.get("candidate_sha") != head:
            errors.append(f"{profile}: candidate_sha does not equal expected HEAD")
        if identity.get("production_jar_sha256") != jar_sha:
            errors.append(f"{profile}: production JAR identity differs from correctness gate")
        if identity.get("native_dylib_sha256") != dylib_sha:
            errors.append(f"{profile}: native dylib identity differs from correctness gate")
        if decision.get("state") != "accepted-candidate":
            errors.append(f"{profile}: decision={decision.get('state')!r}")
        identities[profile] = identity
        decisions[profile] = decision

    if len(identities) == len(PROFILES):
        reference = identities["V1"]
        for profile in ("I0", "I1"):
            for key in COMMON_KEYS:
                if identities[profile].get(key) != reference.get(key):
                    errors.append(
                        f"{profile}: common identity mismatch for {key}: "
                        f"{identities[profile].get(key)!r} != {reference.get(key)!r}"
                    )
        for profile in ("I0", "I1"):
            for key in SHADER_KEYS:
                value = identities[profile].get(key)
                if not isinstance(value, str) or not value or value == "unknown":
                    errors.append(f"{profile}: missing shader identity field {key}")

    state = "accepted-candidate" if not errors else "rejected-candidate"
    result = {
        "schema_version": 1,
        "stage": "P1-metal4-main-production",
        "state": state,
        "source_sha": head,
        "correctness_production_jar_sha256": jar_sha,
        "correctness_native_dylib_sha256": dylib_sha,
        "required_profiles": list(PROFILES),
        "profile_states": {
            profile: decisions.get(profile, {}).get("state", "missing") for profile in PROFILES
        },
        "shared_identity_fields": list(COMMON_KEYS),
        "errors": errors,
        "reason": (
            "V1, I0 and I1 independently accepted the exact correctness-approved P1 production bits under matched physical Metal 4/residency trials"
            if not errors
            else "one or more P1 physical performance profiles or cross-profile identity invariants failed"
        ),
    }
    return result, 0 if not errors else 3


def synthetic_identity(profile: str, head: str, jar: str, dylib: str) -> dict[str, Any]:
    identity: dict[str, Any] = {
        "profile_id": profile,
        "candidate_sha": head,
        "production_jar_sha256": jar,
        "native_dylib_sha256": dylib,
        "world_sha256": "4" * 64,
        "world_scenario_id": "fixed-camera-v1",
        "resolution": [1708, 960],
        "ui_scale": 3,
        "render_distance": 16,
        "camera_pose": "fixed",
        "camera_script_sha256": "5" * 64,
        "minecraft_version": "26.2",
        "sodium_version": "test",
        "macos_version": "26.6",
        "java_version": "25",
    }
    if profile in ("I0", "I1"):
        identity.update({
            "shader_pack_name": "Potato" if profile == "I0" else "BSL",
            "shader_pack_version": "test-version",
            "shader_pack_sha256": ("6" if profile == "I0" else "7") * 64,
            "shader_options_sha256": "8" * 64,
            "iris_version": "test",
        })
    return identity


def write_profile(root: Path, profile: str, identity: dict[str, Any], state: str = "accepted-candidate") -> None:
    profile_root = root / "profiles" / profile
    profile_root.mkdir(parents=True, exist_ok=True)
    (profile_root / "environment.json").write_text(
        json.dumps({"identity": identity}, indent=2) + "\n", encoding="utf-8"
    )
    (profile_root / "decision.json").write_text(
        json.dumps({"state": state}, indent=2) + "\n", encoding="utf-8"
    )


def self_test() -> None:
    head = "1" * 40
    jar = "2" * 64
    dylib = "3" * 64
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        for profile in PROFILES:
            write_profile(root, profile, synthetic_identity(profile, head, jar, dylib))
        result, code = evaluate(root, head, jar, dylib)
        assert code == 0 and result["state"] == "accepted-candidate", result

        i1_environment = root / "profiles/I1/environment.json"
        broken = load_object(i1_environment)
        broken["identity"]["world_sha256"] = "9" * 64
        i1_environment.write_text(json.dumps(broken), encoding="utf-8")
        result, code = evaluate(root, head, jar, dylib)
        assert code == 3 and any("world_sha256" in error for error in result["errors"]), result

        write_profile(root, "I1", synthetic_identity("I1", head, jar, dylib))
        (root / "profiles/I0/decision.json").write_text(
            json.dumps({"state": "rejected-candidate"}), encoding="utf-8"
        )
        result, code = evaluate(root, head, jar, dylib)
        assert code == 3 and result["profile_states"]["I0"] == "rejected-candidate", result

        write_profile(root, "I0", synthetic_identity("I0", head, jar, dylib))
        i0_environment = root / "profiles/I0/environment.json"
        broken = load_object(i0_environment)
        del broken["identity"]["shader_options_sha256"]
        i0_environment.write_text(json.dumps(broken), encoding="utf-8")
        result, code = evaluate(root, head, jar, dylib)
        assert code == 3 and any("shader_options_sha256" in error for error in result["errors"]), result

    print("check_metal4_main_profile_matrix self-test: PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", type=Path)
    parser.add_argument("--expected-head")
    parser.add_argument("--expected-jar-sha")
    parser.add_argument("--expected-dylib-sha")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0
    if args.root is None or not args.expected_head or not args.expected_jar_sha or not args.expected_dylib_sha:
        parser.error("root and all expected identity arguments are required unless --self-test is used")

    result, code = evaluate(
        args.root,
        args.expected_head,
        args.expected_jar_sha,
        args.expected_dylib_sha,
    )
    output = args.output or (args.root / "decision.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
