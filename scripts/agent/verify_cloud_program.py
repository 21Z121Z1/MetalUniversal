#!/usr/bin/env python3
"""Compatibility entrypoint for the current hosted-Metal control contract.

The historical cloud-first program and branch migration matrix are provenance only;
this verifier derives current branch authority from system-registry.json.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "docs/agent/system-registry.json"
P1 = ROOT / "docs/agent/metal4-main-production-acceptance.json"
WORKFLOW = ROOT / ".github/workflows/metal-capabilities.yml"
PROBE = ROOT / ".github/ci/HostedMetalCapabilityProbe.swift"
HOSTED_GRADLE = ROOT / ".github/ci/HostedMetalGradle.init.gradle"
PRESENTATION_TEST = ROOT / "src/test/java/com/metallum/client/metal/render/MetalDevicePresentationContractTest.java"


def fail(message: str) -> None:
    raise SystemExit(f"Hosted Metal control invariant failed: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load(path: Path):
    require(path.is_file(), f"missing {path.relative_to(ROOT)}")
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def run_git(*args: str) -> str:
    try:
        return subprocess.check_output(
            ["git", *args], cwd=ROOT, text=True, stderr=subprocess.STDOUT
        ).strip()
    except (OSError, subprocess.CalledProcessError) as exc:
        fail(f"git {' '.join(args)} failed: {exc}")


def live_remote_branches() -> set[str]:
    output = run_git("ls-remote", "--heads", "origin")
    branches: set[str] = set()
    prefix = "refs/heads/"
    for raw_line in output.splitlines():
        fields = raw_line.split()
        if len(fields) != 2 or not fields[1].startswith(prefix):
            fail(f"malformed git ls-remote branch record: {raw_line!r}")
        branches.add(fields[1][len(prefix):])
    require(branches, "live GitHub branch inventory is empty")
    return branches


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--verify-remote-branches", "--inspect-remote-branches",
        dest="inspect_remote_branches",
        action="store_true",
        help="inspect live refs, require canonical persistent refs, and report branch-policy drift; historical matrices are not live inventory",
    )
    parser.add_argument(
        "--expected-source-sha",
        help="require checkout HEAD to equal this exact 40-hex candidate SHA",
    )
    args = parser.parse_args()

    registry = load(REGISTRY)
    p1 = load(P1)
    canonical = registry.get("canonical", {})
    development = canonical.get("development_branch")
    stable = canonical.get("stable_branch")
    history_anchor = canonical.get("history_anchor_branch")
    platform_branches = canonical.get("platform_branches", [])
    branch_budget = int(canonical.get("max_persistent_branches", 5))
    require(development and stable and history_anchor, "registry must declare development/stable/history branches")
    require(isinstance(platform_branches, list), "registry platform_branches must be a list")

    checked_out_sha = run_git("rev-parse", "HEAD").lower()
    require(re.fullmatch(r"[0-9a-f]{40}", checked_out_sha) is not None,
            f"checkout HEAD is not a full Git SHA: {checked_out_sha!r}")
    if args.expected_source_sha:
        expected = args.expected_source_sha.strip().lower()
        require(re.fullmatch(r"[0-9a-f]{40}", expected) is not None,
                f"expected source SHA is malformed: {expected!r}")
        require(checked_out_sha == expected,
                f"checkout/source SHA mismatch: checkout={checked_out_sha} expected={expected}")

    # Preserve physical-acceptance boundaries independently of any retired stage/branch plan.
    require(p1["scope"]["default_enablement_allowed_before_acceptance"] is False,
            "P1 default enablement gate was weakened")
    require(p1["capability_policy"]["blocked_capability_is_stage_pass"] is False,
            "blocked physical capability may not count as a P1 pass")
    require(p1["capability_policy"]["physical_mac_activation_required_to_close_stage"] is True,
            "P1 physical Mac activation requirement was weakened")
    require(p1["performance_policy"]["paired_performance_required_before_default_enablement"] is True,
            "P1 paired performance requirement was weakened")

    remote: set[str] | None = None
    required_persistent = {stable, development, history_anchor, *platform_branches}
    missing_required: list[str] = []
    if args.inspect_remote_branches:
        remote = live_remote_branches()
        missing_required = sorted(required_persistent - remote)
        require(not missing_required,
                f"required persistent branches missing from GitHub: {missing_required}")

    require(WORKFLOW.is_file(), "missing Metal capability workflow")
    workflow_text = WORKFLOW.read_text(encoding="utf-8").lower()
    for forbidden in ("amethyst", "iphoneos", "iphonesimulator", "simctl", "buildiosnative", "buildiosspvc"):
        require(forbidden not in workflow_text,
                f"Mac-only workflow contains forbidden platform-specific token {forbidden!r}")
    require("runs-on: macos-26" in workflow_text, "hosted Metal job must run on macos-26")
    require("hostedmetalcapabilityprobe.swift" in workflow_text,
            "workflow does not compile the repository-owned hosted Metal probe")
    require(
        "--verify-remote-branches" in workflow_text or "--inspect-remote-branches" in workflow_text,
        "hosted control job must inspect current remote branch policy",
    )
    require("--expected-source-sha" in workflow_text,
            "hosted control job must bind evidence to the exact candidate SHA")
    require("metallum_source_sha" in workflow_text,
            "workflow must carry an explicit exact candidate SHA identity")
    require(workflow_text.count("-i .github/ci/hostedmetalgradle.init.gradle") >= 2,
            "hosted Gradle policy must apply to compile and shipping GPU execution")
    require("cloud_complete_final_physical_pending" in workflow_text,
            "hosted lane must preserve the formal deferred-physical decision")
    require("raise systemexit(failure_reason)" in workflow_text,
            "hosted lane must fail closed when exact-head external gates are incomplete")

    require(HOSTED_GRADLE.is_file(), "missing hosted Gradle capability harness")
    hosted_gradle_text = HOSTED_GRADLE.read_text(encoding="utf-8").lower()
    require("buildiosnative" in hosted_gradle_text and "buildiosspvc" in hosted_gradle_text,
            "Mac-only harness must explicitly neutralize transitive iOS packaging producers")
    require("task.enabled = false" in hosted_gradle_text,
            "Mac-only harness must disable transitive iOS packaging producers")
    require("metaldevicepresentationcontracttest" in hosted_gradle_text,
            "hosted core suite must execute the layerless-device presentation contract")
    require("-xx:errorfile=" in hosted_gradle_text,
            "hosted JVM crashes must preserve HotSpot native-fatal evidence")
    require("mtl_hud_encoder_timing_enabled" in hosted_gradle_text,
            "hosted runner must explicitly disable unsupported HUD encoder timing")

    require(PRESENTATION_TEST.is_file(), "missing layerless-device presentation contract test")
    presentation = PRESENTATION_TEST.read_text(encoding="utf-8").lower()
    require("memorysegment.null" in presentation,
            "presentation contract must cover the real layerless sentinel")
    require("memorysegment.ofaddress(1l)" in presentation,
            "presentation contract must cover a non-null layer handle")

    require(PROBE.is_file(), "missing hosted Metal probe")
    probe_text = PROBE.read_text(encoding="utf-8").lower()
    for forbidden in ("targetenvironment(simulator)", "simulator", "iphone", "ios"):
        require(forbidden not in probe_text,
                f"Mac-only hosted probe contains forbidden platform token {forbidden!r}")
    require("mtlcreatesystemdefaultdevice" in probe_text, "probe does not create the real system Metal device")
    require("waituntilcompleted" in probe_text, "probe does not wait for GPU completion")
    require("hosted_metal_gpu_probe_pass" in probe_text, "probe has no explicit success sentinel")

    live_count = len(remote) if remote is not None else None
    branch_policy_compliant = None if remote is None else live_count <= branch_budget
    evidence = {
        "decision": "hosted-metal-control-contract-pass",
        "source_sha": checked_out_sha,
        "canonical_development_branch": development,
        "stable_branch": stable,
        "required_persistent_branches": sorted(required_persistent),
        "live_remote_inventory_inspected": remote is not None,
        "live_remote_branch_count": live_count,
        "branch_budget_after_task_cleanup": branch_budget,
        "branch_policy_compliant_now": branch_policy_compliant,
        "branch_policy_drift": (
            sorted(remote - required_persistent) if remote is not None and not branch_policy_compliant else []
        ),
        "historical_branch_matrix_is_live_authority": False,
        "hosted_mac_only_task_policy": True,
        "layerless_presentation_contract": True,
        "physical_acceptance_deferred_not_waived": True,
        "p1_physical_gate_preserved": True,
    }

    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(evidence, sort_keys=True))


if __name__ == "__main__":
    main()
