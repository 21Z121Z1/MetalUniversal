#!/usr/bin/env python3
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "docs/agent/benchmark-profiles.json"
SHA256_FIELD = re.compile(r".*_sha256$")


def fail(message: str) -> None:
    raise SystemExit(f"benchmark profile contract invalid: {message}")


def main() -> None:
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        fail("schema_version must be 1")
    if data.get("reference_branch") != "integration/iris-metal-next":
        fail("reference_branch must be integration/iris-metal-next")

    rules = data.get("rules", {})
    required_rules = {
        "mutable_latest_inputs_forbidden": True,
        "external_files_are_content_addressed": True,
        "metalfx_mode": "OFF",
        "frame_generation": False,
        "performance_runs_require_exact_candidate_sha": True,
        "performance_runs_require_world_sha256": True,
        "shader_profiles_require_shader_pack_sha256": True,
    }
    for key, expected in required_rules.items():
        if rules.get(key) != expected:
            fail(f"rules.{key} must be {expected!r}")

    profiles = data.get("profiles")
    if not isinstance(profiles, list):
        fail("profiles must be a list")
    by_id = {profile.get("id"): profile for profile in profiles}
    if set(by_id) != {"V0", "V1", "I0", "I1"}:
        fail(f"profiles must be exactly V0/V1/I0/I1, got {sorted(by_id)}")
    if len(by_id) != len(profiles):
        fail("profile ids must be unique")

    common_identity = {
        "candidate_sha",
        "production_jar_sha256",
        "native_dylib_sha256",
        "world_sha256",
        "world_scenario_id",
        "resolution",
        "render_distance",
        "minecraft_version",
        "sodium_version",
        "macos_version",
        "java_version",
    }
    for profile_id, profile in by_id.items():
        stack = profile.get("stack", {})
        if stack.get("minecraft") != "26.2":
            fail(f"{profile_id} must target Minecraft 26.2")
        if stack.get("sodium") is not True:
            fail(f"{profile_id} must keep Sodium in the production stack")
        if stack.get("metalfx_mode") != "OFF" or stack.get("frame_generation") is not False:
            fail(f"{profile_id} must isolate the base renderer from MetalFX/FG")
        identity = set(profile.get("required_identity", []))
        missing = common_identity - identity
        if missing:
            fail(f"{profile_id} is missing identity fields {sorted(missing)}")
        # Mutable file paths are not identity. Every external binary/script input
        # in a benchmark contract must have a content hash field.
        for field in identity:
            if field.endswith("_path") or field.endswith("_file"):
                fail(f"{profile_id} uses mutable identity field {field}")

    for profile_id in ("V0", "V1"):
        stack = by_id[profile_id]["stack"]
        if stack.get("iris_semantic") is not False or stack.get("shader_pack") is not None:
            fail(f"{profile_id} must be the no-shader baseline")

    for profile_id, family in (("I0", "Potato"), ("I1", "BSL")):
        profile = by_id[profile_id]
        stack = profile["stack"]
        if stack.get("iris_semantic") is not True or stack.get("shader_pack") != family:
            fail(f"{profile_id} must enable Iris semantic with {family}")
        identity = set(profile["required_identity"])
        for field in ("iris_version", "shader_pack_name", "shader_pack_version", "shader_pack_sha256", "shader_options_sha256"):
            if field not in identity:
                fail(f"{profile_id} is missing shader identity field {field}")

    workloads = data.get("terrain_workloads")
    if not isinstance(workloads, list):
        fail("terrain_workloads must be a list")
    workload_by_id = {workload.get("id"): workload for workload in workloads}
    if set(workload_by_id) != {"T0", "T1"}:
        fail(f"terrain workloads must be exactly T0/T1, got {sorted(workload_by_id)}")
    if workload_by_id["T0"].get("camera_motion") != "none":
        fail("T0 must be the static workload")
    if workload_by_id["T1"].get("camera_motion") != "content-addressed-script":
        fail("T1 must use a content-addressed camera script")
    if "camera_script_sha256" not in workload_by_id["T1"].get("requires", []):
        fail("T1 must require camera_script_sha256")
    if "time_to_first_visible_ms" not in workload_by_id["T1"].get("authoritative_metrics", []):
        fail("T1 must expose time_to_first_visible_ms")

    measurement = data.get("measurement", {})
    if measurement.get("warmup_seconds_min", 0) < 30:
        fail("warmup must be at least 30 seconds")
    if measurement.get("sample_seconds_min", 0) < 120:
        fail("sample window must be at least 120 seconds")
    if measurement.get("minimum_paired_blocks", 0) < 4:
        fail("at least four paired blocks are required")
    if measurement.get("minimum_positive_block_fraction", 0.0) < 0.75:
        fail("positive block fraction must be at least 0.75")
    if measurement.get("correctness_gate_required_before_performance") is not True:
        fail("correctness must gate performance")

    print(f"benchmark profile contract: PASS ({MANIFEST.relative_to(ROOT)})")


if __name__ == "__main__":
    main()
