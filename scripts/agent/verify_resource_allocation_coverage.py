#!/usr/bin/env python3
"""Check source coverage for the native resource-allocation wrappers.

This is deliberately a source-structure check.  It proves that direct Metal
factory calls are routed through the tracked wrappers and that the immutable
diagnostic gate/registry hooks are present.  It does not prove runtime
ownership, deallocation, or lifetime coverage.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Mapping


ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "src" / "main" / "native"
LEDGER = NATIVE / "ResourceAllocationLedger.swift"
METALLUM = NATIVE / "MetallumNative.swift"

_COMMENT_OR_STRING = re.compile(
    r"/\*.*?\*/|//[^\n]*|\"\"\".*?\"\"\"|\"(?:\\.|[^\"\\])*\"",
    re.DOTALL,
)
_FUNCTION = re.compile(r"\bfunc\s+([A-Za-z_]\w*)\s*\(")
_RAW_METHOD = re.compile(r"\.\s*(makeBuffer|makeTexture|makeIndirectCommandBuffer)\s*\(")
_HEAP_FACTORY = re.compile(r"(?<![A-Za-z0-9_])(?:\.\s*)?makeHeap\s*\(")


def _mask_noncode(source: str) -> str:
    """Blank comments and strings while preserving newlines and offsets."""

    return _COMMENT_OR_STRING.sub(
        lambda match: "".join("\n" if char == "\n" else " " for char in match.group()),
        source,
    )


def _line_number(source: str, offset: int) -> int:
    return source.count("\n", 0, offset) + 1


def _function_ranges(masked: str) -> list[tuple[str, int, int]]:
    """Return (name, body-start, body-end) for brace-delimited functions."""

    functions: list[tuple[str, int, int]] = []
    for match in _FUNCTION.finditer(masked):
        brace = masked.find("{", match.end())
        if brace < 0:
            continue
        depth = 0
        end = -1
        for index in range(brace, len(masked)):
            if masked[index] == "{":
                depth += 1
            elif masked[index] == "}":
                depth -= 1
                if depth == 0:
                    end = index + 1
                    break
        if end >= 0:
            functions.append((match.group(1), brace + 1, end - 1))
    return functions


def _function_at(functions: list[tuple[str, int, int]], offset: int) -> str | None:
    for name, start, end in functions:
        if start <= offset < end:
            return name
    return None


def _normalise(code: str) -> str:
    return re.sub(r"\s+", " ", code).strip()


def _wrapper_bodies(
    source: str, masked: str, name: str
) -> list[tuple[str, str]]:
    """Return (source body, masked body) for all overloads of a wrapper."""

    result: list[tuple[str, str]] = []
    for match in _FUNCTION.finditer(masked):
        if match.group(1) != name:
            continue
        brace = masked.find("{", match.end())
        if brace < 0:
            continue
        depth = 0
        end = -1
        for index in range(brace, len(masked)):
            if masked[index] == "{":
                depth += 1
            elif masked[index] == "}":
                depth -= 1
                if depth == 0:
                    end = index
                    break
        if end >= 0:
            result.append((source[brace + 1 : end], masked[brace + 1 : end]))
    return result


def _copy_sources() -> dict[Path, str]:
    if not NATIVE.is_dir():
        return {}
    return {path: path.read_text(encoding="utf-8") for path in sorted(NATIVE.rglob("*.swift"))}


def _check_wrapper(
    errors: list[str],
    ledger_source: str,
    ledger_masked: str,
    name: str,
    expected_factory: str,
    expected_kind: str,
    expected_count: int,
) -> None:
    bodies = _wrapper_bodies(ledger_source, ledger_masked, name)
    if len(bodies) != expected_count:
        errors.append(f"ledger must define {expected_count} {name} overload(s), found {len(bodies)}")
        return

    normalised_factories = [_normalise(body) for _, body in bodies]
    if name == "makeTrackedBuffer":
        expected_variants = [
            "makeBuffer(length: length, options: options)",
            "makeBuffer(bytes: bytes, length: length, options: options)",
        ]
        for expected in expected_variants:
            matches = [body for body in normalised_factories if expected in body]
            if len(matches) != 1:
                errors.append(f"{name} must contain exactly one underlying {expected}")

    for source_body, masked_body in bodies:
        # Use masked text for all contract counts so a comment/string cannot
        # manufacture a factory or registry hook.
        body = _normalise(masked_body)
        selected_factory = expected_factory
        if name == "makeTrackedBuffer" and expected_factory not in body:
            byte_factory = "makeBuffer(bytes: bytes, length: length, options: options)"
            if byte_factory in body:
                selected_factory = byte_factory
            else:
                errors.append(f"{name} is missing an underlying buffer factory")
                continue
        if selected_factory not in body or body.count(selected_factory) != 1:
            errors.append(f"{name} must invoke underlying {selected_factory} exactly once")
        gate = "if ResourceAllocationLedger.enabledAtStartup"
        register = f"ResourceAllocationLedger.shared.register(resource, kind: {expected_kind})"
        if body.count(gate) != 1:
            errors.append(f"{name} must have exactly one immutable-gate branch")
        if body.count(register) != 1:
            errors.append(f"{name} must register exactly one {expected_kind} resource")
        factory_pos = body.find(selected_factory)
        gate_pos = body.find(gate)
        register_pos = body.find(register)
        if not (factory_pos >= 0 and factory_pos < gate_pos < register_pos):
            errors.append(f"{name} must factory-create, then gate, then register")

    # The normalised masked body is only used for gate counting.  This keeps a
    # comment/string containing the gate text from satisfying the contract.
    del ledger_source, ledger_masked


def _check_ledger(errors: list[str], source: str) -> None:
    masked = _mask_noncode(source)
    if not re.search(r"\bfinal\s+class\s+ResourceAllocationLedger\b", masked):
        errors.append("ledger class ResourceAllocationLedger is missing")
    if not re.search(r"\bstatic\s+let\s+enabledAtStartup\b", masked):
        errors.append("enabledAtStartup must be an immutable static let")
    if re.search(r"\bstatic\s+var\s+enabledAtStartup\b", masked):
        errors.append("enabledAtStartup must not be mutable")
    if "ProcessInfo.processInfo.environment" not in masked:
        errors.append("enabledAtStartup must read ProcessInfo.processInfo.environment")
    if 'METALLUM_RESOURCE_ALLOCATION_TRACE' not in source:
        errors.append("enabledAtStartup environment key is missing")
    if not re.search(r"METALLUM_RESOURCE_ALLOCATION_TRACE[\s\S]*?==\s*\"1\"", source):
        errors.append("enabledAtStartup must compare METALLUM_RESOURCE_ALLOCATION_TRACE with 1")

    _check_wrapper(
        errors,
        source,
        masked,
        "makeTrackedBuffer",
        "makeBuffer(length: length, options: options)",
        ".buffer",
        2,
    )
    _check_wrapper(
        errors,
        source,
        masked,
        "makeTrackedTexture",
        "makeTexture(descriptor: descriptor)",
        ".texture",
        1,
    )
    _check_wrapper(
        errors,
        source,
        masked,
        "makeTrackedIndirectCommandBuffer",
        "makeIndirectCommandBuffer( descriptor: descriptor, maxCommandCount: maxCommandCount, options: options )",
        ".indirectCommandBuffer",
        1,
    )


def check_sources(sources: Mapping[Path, str]) -> list[str]:
    """Return structural errors for an in-memory native Swift source tree."""

    errors: list[str] = []
    if LEDGER not in sources:
        errors.append(f"missing {LEDGER}")
    if METALLUM not in sources:
        errors.append(f"missing {METALLUM}")
    ledger_source = sources.get(LEDGER)
    if ledger_source is not None:
        _check_ledger(errors, ledger_source)

    for path, source in sorted(sources.items(), key=lambda item: str(item[0])):
        if path.suffix != ".swift":
            continue
        masked = _mask_noncode(source)
        functions = _function_ranges(masked)
        # Heap allocation has no permitted path, including inside the ledger.
        for match in _HEAP_FACTORY.finditer(masked):
            errors.append(
                f"raw makeHeap is forbidden at {path}:{_line_number(source, match.start())}"
            )

        for match in _RAW_METHOD.finditer(masked):
            method = match.group(1)
            receiver = masked[max(0, match.start() - 80) : match.start()]
            is_alias = method == "makeTexture" and re.search(
                r"\bbuffer\s*$", receiver
            ) is not None
            if path == METALLUM and is_alias:
                continue
            if path == LEDGER:
                # The ledger is the only file allowed to contain its direct
                # factory implementation.  Wrapper-body checks above prove
                # each expected call and registry hook.
                continue
            errors.append(
                f"raw {method} is not tracked at {path}:{_line_number(source, match.start())}"
            )

        if path == METALLUM:
            alias_matches = list(re.finditer(r"\bbuffer\s*\.\s*makeTexture\s*\(", masked))
            if len(alias_matches) != 1:
                errors.append(
                    "MetallumNative.swift must have exactly one buffer.makeTexture alias view"
                )
            for match in alias_matches:
                function = _function_at(functions, match.start())
                if function != "metallum_create_buffer_texture_view":
                    errors.append(
                        "buffer.makeTexture is only allowed in metallum_create_buffer_texture_view"
                    )
            # Any texture factory with a different receiver remains a raw
            # allocation and is rejected by _RAW_METHOD above.

    return errors


def _expect_failure(label: str, sources: Mapping[Path, str]) -> None:
    errors = check_sources(sources)
    if not errors:
        raise AssertionError(f"self-test mutation unexpectedly passed: {label}")


def run_self_test() -> None:
    baseline = _copy_sources()
    baseline_errors = check_sources(baseline)
    if baseline_errors:
        raise AssertionError("baseline source tree fails: " + "; ".join(baseline_errors))
    masked_fixture = _mask_noncode('// device.makeBuffer()\nlet s = "device.makeHeap()"')
    assert "makeBuffer" not in masked_fixture and "makeHeap" not in masked_fixture
    assert "\n" in masked_fixture, "masking regression"

    mutated = dict(baseline)
    mutated[METALLUM] = mutated[METALLUM].replace(
        "device.makeTrackedBuffer(", "device.makeBuffer(", 1
    )
    _expect_failure("restored raw factory", mutated)

    mutated = dict(baseline)
    bypass = NATIVE / "ResourceAllocationBypass.swift"
    mutated[bypass] = "import Metal\nfunc bypass(_ device: MTLDevice) { _ = device.makeTexture(descriptor: MTLTextureDescriptor()) }\n"
    _expect_failure("new-file raw creation", mutated)

    mutated = dict(baseline)
    mutated[METALLUM] += '\n// device.makeBuffer(length: 1, options: [])\nlet ignored = "device.makeHeap(descriptor: x)"\n'
    if check_sources(mutated):
        raise AssertionError("comments/string mutation was counted as raw allocation")

    mutated = dict(baseline)
    mutated[METALLUM] += '\nfunc wrongAlias(_ buffer: MTLBuffer, _ descriptor: MTLTextureDescriptor) { _ = buffer.makeTexture(descriptor: descriptor, offset: 0, bytesPerRow: 1) }\n'
    _expect_failure("alias exception in wrong function", mutated)

    mutated = dict(baseline)
    mutated[LEDGER] += '\nfunc badHeap(_ device: MTLDevice) { _ = device.makeHeap(descriptor: MTLHeapDescriptor()) }\n'
    _expect_failure("heap creation in ledger", mutated)

    mutated = dict(baseline)
    mutated[LEDGER] = mutated[LEDGER].replace(
        "ResourceAllocationLedger.shared.register(resource, kind: .texture)", "", 1
    )
    _expect_failure("missing registry hook", mutated)

    mutated = dict(baseline)
    mutated[LEDGER] = mutated[LEDGER].replace(
        "static let enabledAtStartup", "static var enabledAtStartup", 1
    )
    _expect_failure("mutable environment gate", mutated)

    mutated = dict(baseline)
    mutated[METALLUM] = mutated[METALLUM].replace(
        'metallum_create_buffer_texture_view', 'metallum_wrong_alias_view', 2
    )
    _expect_failure("renamed alias function", mutated)

    print("resource allocation source coverage self-test: PASS")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run in-memory mutation tests instead of checking the current tree",
    )
    args = parser.parse_args(argv)
    try:
        if args.self_test:
            run_self_test()
            return 0
        errors = check_sources(_copy_sources())
    except (OSError, UnicodeError) as exc:
        print(f"resource allocation source coverage: ERROR: {exc}", file=sys.stderr)
        return 2
    if errors:
        print("resource allocation source coverage: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(
        "resource allocation source coverage: PASS "
        "(structural only; no runtime lifetime proof)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
