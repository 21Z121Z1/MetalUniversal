#!/usr/bin/env python3
import argparse
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROGRAM = ROOT / "docs/agent/cloud-first-metal-program.json"
MATRIX = ROOT / "docs/agent/branch-migration-matrix.json"
P1 = ROOT / "docs/agent/metal4-main-production-acceptance.json"
WORKFLOW = ROOT / ".github/workflows/metal-capabilities.yml"
PROBE = ROOT / ".github/ci/HostedMetalCapabilityProbe.swift"


def fail(message: str) -> None:
    raise SystemExit(f"Cloud Metal program invariant failed: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load(path: Path):
    require(path.is_file(), f"missing {path.relative_to(ROOT)}")
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def live_remote_branches() -> set[str]:
    try:
        output = subprocess.check_output(
            ["git", "ls-remote", "--heads", "origin"],
            cwd=ROOT,
            text=True,
            stderr=subprocess.STDOUT,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        fail(f"unable to query live GitHub branch inventory from origin: {exc}")

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
        "--verify-remote-branches",
        action="store_true",
        help="fail closed unless the migration matrix exactly matches git ls-remote --heads origin",
    )
    args = parser.parse_args()

    program = load(PROGRAM)
    matrix = load(MATRIX)
    p1 = load(P1)

    require(program["canonical_branch"] == "integration/iris-metal-next", "canonical branch changed unexpectedly")
    require(program["program_branch"] == "agent/metal-eval-v3", "program branch mismatch")
    require(program["platform_scope"]["primary"] == "macOS Apple Silicon", "primary platform must remain macOS Apple Silicon")
    require(program["platform_scope"]["amethyst_specific_compatibility"] is False, "Amethyst compatibility must remain out of scope")
    require(program["platform_scope"]["ios_specific_adaptation"] is False, "iOS adaptation must remain out of scope")
    require(program["principles"]["physical_acceptance_is_deferred_not_waived"] is True, "physical acceptance may not be waived")
    require(program["principles"]["skipped_or_cancelled_required_gate_is_pass"] is False, "skipped/cancelled gates cannot pass")
    require(program["principles"]["unsupported_capability_is_pass"] is False, "unsupported capability cannot pass")

    branches = matrix.get("branches", [])
    names = [entry.get("branch") for entry in branches]
    require(all(isinstance(name, str) and name for name in names), "branch migration matrix contains an invalid branch name")
    require(len(names) == len(set(names)), "branch migration matrix contains duplicate branch names")
    audited = set(names)

    remote = None
    if args.verify_remote_branches:
        remote = live_remote_branches()
        missing = sorted(remote - audited)
        stale = sorted(audited - remote)
        require(
            not missing and not stale,
            f"branch migration matrix does not match live GitHub inventory: missing={missing}, stale={stale}",
        )

    require("integration/iris-metal-next" in audited, "canonical branch missing from migration matrix")
    require("agent/metal-eval-v3" in audited, "active C0 branch missing from migration matrix")
    require("feature/ios-amethyst-runtime" in audited and "codex/amethyst-ios-runtime-262" in audited,
            "out-of-scope platform branches must be explicitly recorded")
    for entry in branches:
        if entry.get("role") == "out-of-scope-platform-lineage":
            require("do-not-port" in entry.get("disposition", ""),
                    f"{entry['branch']} must remain non-portable as a platform lineage")

    require(p1["scope"]["default_enablement_allowed_before_acceptance"] is False, "P1 default enablement gate was weakened")
    require(p1["capability_policy"]["blocked_capability_is_stage_pass"] is False, "blocked physical capability may not count as a P1 pass")
    require(p1["capability_policy"]["physical_mac_activation_required_to_close_stage"] is True, "P1 physical Mac activation requirement was weakened")
    require(p1["performance_policy"]["paired_performance_required_before_default_enablement"] is True, "P1 paired performance requirement was weakened")

    require(WORKFLOW.is_file(), "missing Mac-only metal-capabilities workflow")
    workflow_text = WORKFLOW.read_text(encoding="utf-8").lower()
    for forbidden in ("amethyst", "iphoneos", "iphonesimulator", "simctl", "buildiosnative", "buildiosspvc"):
        require(forbidden not in workflow_text, f"Mac-only workflow contains forbidden platform-specific token {forbidden!r}")
    require("runs-on: macos-26" in workflow_text, "hosted Metal job must run on macos-26")
    require("hostedmetalgpuprobe" not in workflow_text, "workflow must use the canonical HostedMetalCapabilityProbe source path instead of an ad-hoc binary contract")
    require("hostedmetalcapabilityprobe.swift" in workflow_text, "workflow does not compile the repository-owned hosted Metal probe")
    require("--verify-remote-branches" in workflow_text,
            "cloud contract job must verify the migration matrix against live GitHub branches")

    require(PROBE.is_file(), "missing hosted Metal probe")
    probe_text = PROBE.read_text(encoding="utf-8").lower()
    for forbidden in ("targetenvironment(simulator)", "simulator", "iphone", "ios"):
        require(forbidden not in probe_text, f"Mac-only hosted probe contains forbidden platform-specific token {forbidden!r}")
    require("mtlcreatesystemdefaultdevice" in probe_text, "probe does not create the real system Metal device")
    require("waituntilcompleted" in probe_text, "probe does not wait for GPU completion")
    require("hosted_metal_gpu_probe_pass" in probe_text, "probe has no explicit success sentinel")

    evidence = {
        "decision": "cloud-program-contract-pass",
        "canonical_branch": program["canonical_branch"],
        "program_branch": program["program_branch"],
        "audited_branch_count": len(branches),
        "audited_branches": sorted(audited),
        "live_remote_inventory_verified": remote is not None,
        "live_remote_branch_count": len(remote) if remote is not None else None,
        "physical_acceptance_deferred_not_waived": True,
        "amethyst_specific_compatibility": False,
        "ios_specific_adaptation": False,
        "p1_physical_gate_preserved": True,
    }

    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    print(json.dumps(evidence, sort_keys=True))


if __name__ == "__main__":
    main()
