#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROGRAM = ROOT / "docs/agent/cloud-first-metal-program.json"
MATRIX = ROOT / "docs/agent/branch-migration-matrix.json"
P1 = ROOT / "docs/agent/metal4-main-production-acceptance.json"
WORKFLOW = ROOT / ".github/workflows/metal-capabilities.yml"
PROBE = ROOT / ".github/ci/HostedMetalCapabilityProbe.swift"

EXPECTED_BRANCHES = {
    "agent/metal-eval-v3",
    "archive/metal4-geometry-handoff-2026-07-28",
    "archive/metal-iris-beta-2026-08-02",
    "archive/metalfx-v1-prototype-2026-08-02",
    "ci/minecraft-client-e2e-20260815",
    "ci/minecraft-client-e2e-p0-20260819",
    "codex/amethyst-ios-runtime-262",
    "codex/autonomous-metal-next",
    "codex/autonomous-metal-next-20260813",
    "codex/bsl-metalfx-framegen-cutout-20260813",
    "codex/p1-binding-shadow-safe",
    "codex/p1-uniform-token-ci",
    "feature/ios-amethyst-runtime",
    "feature/iris-semantic-completion",
    "feature/metal4-main-production",
    "feature/metalfx-framegen-contracts",
    "feature/token-native-private-bindings",
    "fix/metal4-arena-lifetime",
    "integration/iris-metal-next",
    "master",
    "perf/swift-performance-by-design-20260819",
    "perf/swift-performance-by-design-final-20260819",
    "perf/swift-performance-by-design-final-validation-20260819",
    "perf/swift-performance-by-design-ready-20260819",
    "research/iris-semantic-contracts-alt",
}


def fail(message: str) -> None:
    raise SystemExit(f"Cloud Metal program invariant failed: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load(path: Path):
    require(path.is_file(), f"missing {path.relative_to(ROOT)}")
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
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
    require(len(names) == len(set(names)), "branch migration matrix contains duplicate branch names")
    actual = set(names)
    missing = sorted(EXPECTED_BRANCHES - actual)
    unexpected = sorted(actual - EXPECTED_BRANCHES)
    require(not missing and not unexpected,
            f"branch migration matrix inventory drifted: missing={missing}, unexpected={unexpected}")
    require(len(branches) == len(EXPECTED_BRANCHES),
            f"expected {len(EXPECTED_BRANCHES)} audited branches, got {len(branches)}")
    require("integration/iris-metal-next" in names, "canonical branch missing from migration matrix")
    require("agent/metal-eval-v3" in names, "active C0 branch missing from migration matrix")
    require("feature/ios-amethyst-runtime" in names and "codex/amethyst-ios-runtime-262" in names,
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
        "audited_branches": sorted(actual),
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
