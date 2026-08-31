#!/usr/bin/env python3
import argparse
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROGRAM = ROOT / "docs/agent/cloud-first-metal-program.json"
MATRIX = ROOT / "docs/agent/branch-migration-matrix.json"
P1 = ROOT / "docs/agent/metal4-main-production-acceptance.json"
WORKFLOW = ROOT / ".github/workflows/metal-capabilities.yml"
PROBE = ROOT / ".github/ci/HostedMetalCapabilityProbe.swift"
HOSTED_GRADLE = ROOT / ".github/ci/HostedMetalGradle.init.gradle"
PRESENTATION_TEST = ROOT / "src/test/java/com/metallum/client/metal/render/MetalDevicePresentationContractTest.java"

# Long-lived branch policy from the repository AGENTS.md. The migration matrix is
# historical provenance and deliberately retains branches after they are retired,
# so it must not be treated as an exact snapshot of refs/heads.
REQUIRED_PERSISTENT_BRANCHES = frozenset({
    "master",
    "integration/iris-metal-next",
    "feature/ios-amethyst-runtime",
    "research/modernization-backlog",
})


def fail(message: str) -> None:
    raise SystemExit(f"Cloud Metal program invariant failed: {message}")


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
            ["git", *args],
            cwd=ROOT,
            text=True,
            stderr=subprocess.STDOUT,
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


def classify_remote_branch_inventory(remote: set[str], audited: set[str]) -> dict:
    """Separate capability invariants from branch-lifecycle bookkeeping.

    The migration matrix intentionally preserves retired source branches as
    provenance. Live disposable feature branches may also appear between matrix
    updates. Neither condition changes Metal capability. Only disappearance of
    the repository's required persistent refs is a hard capability-program
    invariant; the other deltas remain visible in structured evidence so branch
    hygiene can be handled by its own lifecycle workflow.
    """
    return {
        "required_persistent": sorted(REQUIRED_PERSISTENT_BRANCHES),
        "missing_required_persistent": sorted(REQUIRED_PERSISTENT_BRANCHES - remote),
        "live_not_in_migration_matrix": sorted(remote - audited),
        "documented_not_live": sorted(audited - remote),
    }


def require_string_list(value, field: str, *, allow_empty: bool = False) -> list[str]:
    require(isinstance(value, list), f"{field} must be a list")
    require(allow_empty or len(value) > 0, f"{field} must not be empty")
    require(all(isinstance(item, str) and item.strip() for item in value),
            f"{field} must contain only non-empty strings")
    return value


def validate_migration_records(matrix: dict, audited: set[str]) -> list[dict]:
    records = matrix.get("migration_records")
    require(isinstance(records, list) and records,
            "migration matrix must contain at least one bounded migration record for C0")

    required_fields = {
        "source_branch",
        "source_sha",
        "destination_branch",
        "destination_stage",
        "files_components_selected",
        "files_components_deliberately_omitted",
        "current_canonical_equivalent_reviewed",
        "canonical_equivalent",
        "tests_migrated",
        "tests_newly_added",
        "existing_tests_reused",
        "runtime_evidence",
        "performance_evidence",
        "known_deferred_physical_only_checks",
    }
    c0_records = []
    for index, record in enumerate(records):
        require(isinstance(record, dict), f"migration_records[{index}] must be an object")
        missing = sorted(required_fields - set(record))
        require(not missing, f"migration_records[{index}] is missing required fields: {missing}")

        prefix = f"migration_records[{index}]"
        source_branch = record["source_branch"]
        destination_branch = record["destination_branch"]
        require(isinstance(source_branch, str) and source_branch in audited,
                f"{prefix}.source_branch must name an audited branch")
        require(isinstance(destination_branch, str) and destination_branch in audited,
                f"{prefix}.destination_branch must name an audited branch")
        require(re.fullmatch(r"[0-9a-f]{40}", str(record["source_sha"]).lower()) is not None,
                f"{prefix}.source_sha must be an exact 40-hex Git SHA")
        require(record["current_canonical_equivalent_reviewed"] is True,
                f"{prefix} must record current canonical equivalent review")
        require(isinstance(record["canonical_equivalent"], str) and record["canonical_equivalent"].strip(),
                f"{prefix}.canonical_equivalent must be recorded")

        require_string_list(record["files_components_selected"], f"{prefix}.files_components_selected")
        require_string_list(record["files_components_deliberately_omitted"],
                            f"{prefix}.files_components_deliberately_omitted")
        migrated = require_string_list(record["tests_migrated"], f"{prefix}.tests_migrated", allow_empty=True)
        added = require_string_list(record["tests_newly_added"], f"{prefix}.tests_newly_added", allow_empty=True)
        reused = require_string_list(record["existing_tests_reused"], f"{prefix}.existing_tests_reused", allow_empty=True)
        require(bool(migrated or added or reused),
                f"{prefix} must identify migrated, new, or reused tests")
        require_string_list(record["runtime_evidence"], f"{prefix}.runtime_evidence")
        require(record["performance_evidence"] is None or isinstance(record["performance_evidence"], str),
                f"{prefix}.performance_evidence must be null or a string")
        require_string_list(record["known_deferred_physical_only_checks"],
                            f"{prefix}.known_deferred_physical_only_checks")

        if record["destination_stage"] == "C0-agent-metal-lab":
            c0_records.append(record)

    require(c0_records, "C0 must have an explicit migration provenance record")
    require(any(record["source_branch"] == "master" for record in c0_records),
            "C0 must record the proven master hosted-Metal source lineage")
    require(all(record["destination_branch"] == "agent/metal-eval-v3" for record in c0_records),
            "all C0 migration records must target the bounded active branch")
    require(any(
        "MetalDevicePresentationContractTest" in record["tests_newly_added"]
        for record in c0_records
    ), "C0 migration record must include the layerless-device presentation contract test")
    return records


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--verify-remote-branches",
        action="store_true",
        help=(
            "verify required persistent GitHub refs; report live/matrix drift as evidence "
            "without treating retired provenance or disposable branches as capability failures"
        ),
    )
    parser.add_argument(
        "--expected-source-sha",
        help="require the checkout HEAD to equal this exact 40-hex candidate SHA",
    )
    args = parser.parse_args()

    program = load(PROGRAM)
    matrix = load(MATRIX)
    p1 = load(P1)

    checked_out_sha = run_git("rev-parse", "HEAD").lower()
    require(re.fullmatch(r"[0-9a-f]{40}", checked_out_sha) is not None,
            f"checkout HEAD is not a full Git SHA: {checked_out_sha!r}")
    if args.expected_source_sha:
        expected_source_sha = args.expected_source_sha.strip().lower()
        require(re.fullmatch(r"[0-9a-f]{40}", expected_source_sha) is not None,
                f"expected source SHA is malformed: {expected_source_sha!r}")
        require(checked_out_sha == expected_source_sha,
                f"checkout/source SHA mismatch: checkout={checked_out_sha} expected={expected_source_sha}")

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
    branch_inventory = None
    if args.verify_remote_branches:
        remote = live_remote_branches()
        branch_inventory = classify_remote_branch_inventory(remote, audited)
        require(
            not branch_inventory["missing_required_persistent"],
            "required persistent GitHub branches are missing: "
            f"{branch_inventory['missing_required_persistent']}",
        )

    require("integration/iris-metal-next" in audited, "canonical branch missing from migration matrix")
    require("agent/metal-eval-v3" in audited, "active C0 branch missing from migration matrix")
    require("feature/ios-amethyst-runtime" in audited and "codex/amethyst-ios-runtime-262" in audited,
            "out-of-scope platform branches must be explicitly recorded")
    for entry in branches:
        if entry.get("role") == "out-of-scope-platform-lineage":
            require("do-not-port" in entry.get("disposition", ""),
                    f"{entry['branch']} must remain non-portable as a platform lineage")

    migration_records = validate_migration_records(matrix, audited)

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
            "cloud contract job must verify required persistent GitHub branches")
    require("--expected-source-sha" in workflow_text,
            "cloud contract job must bind evidence to the exact candidate SHA")
    require("metallum_source_sha" in workflow_text,
            "workflow must carry an explicit exact candidate SHA identity")
    require(workflow_text.count("-i .github/ci/hostedmetalgradle.init.gradle") >= 2,
            "Mac-only hosted Gradle policy must apply to compile and shipping GPU execution")
    require("cloud_complete_final_physical_pending" in workflow_text,
            "hosted lane must emit the formal C0 success decision")
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
    presentation_test_text = PRESENTATION_TEST.read_text(encoding="utf-8").lower()
    require("memorysegment.null" in presentation_test_text,
            "presentation contract test must cover the real layerless sentinel")
    require("memorysegment.ofaddress(1l)" in presentation_test_text,
            "presentation contract test must cover a non-null layer handle")

    require(PROBE.is_file(), "missing hosted Metal probe")
    probe_text = PROBE.read_text(encoding="utf-8").lower()
    for forbidden in ("targetenvironment(simulator)", "simulator", "iphone", "ios"):
        require(forbidden not in probe_text, f"Mac-only hosted probe contains forbidden platform-specific token {forbidden!r}")
    require("mtlcreatesystemdefaultdevice" in probe_text, "probe does not create the real system Metal device")
    require("waituntilcompleted" in probe_text, "probe does not wait for GPU completion")
    require("hosted_metal_gpu_probe_pass" in probe_text, "probe has no explicit success sentinel")

    evidence = {
        "decision": "cloud-program-contract-pass",
        "source_sha": checked_out_sha,
        "canonical_branch": program["canonical_branch"],
        "program_branch": program["program_branch"],
        "audited_branch_count": len(branches),
        "audited_branches": sorted(audited),
        "migration_record_count": len(migration_records),
        "migration_source_shas": {
            record["source_branch"]: record["source_sha"]
            for record in migration_records
            if record["destination_stage"] == "C0-agent-metal-lab"
        },
        "live_remote_inventory_verified": remote is not None,
        "live_remote_branch_count": len(remote) if remote is not None else None,
        "branch_inventory": branch_inventory,
        "branch_hygiene_requires_followup": bool(
            branch_inventory
            and (branch_inventory["live_not_in_migration_matrix"]
                 or branch_inventory["documented_not_live"])
        ),
        "hosted_mac_only_task_policy": True,
        "layerless_presentation_contract": True,
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
