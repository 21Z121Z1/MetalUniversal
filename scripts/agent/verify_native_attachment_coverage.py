#!/usr/bin/env python3
"""Check structural coverage of the native render attachment action ledger.

This is a source-level backstop.  It proves that the two tracked render
factory overloads, the M3/M4 deferred-store setters, and the generic/M4 render
end overloads are wired to ``AttachmentActionLedger``.  It also keeps the two
deliberately untracked Metal 4 frame-generation render helpers explicit.

It does *not* prove that every runtime path reaches those functions, that the
descriptor values are semantically correct, or that a completed GPU report is
complete.  Those require the native tests and a real runtime ledger receipt.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "src/main/native"
LEDGER = NATIVE / "AttachmentActionLedger.swift"
COUNT = NATIVE / "EncoderCountLedger.swift"
METALLUM = NATIVE / "MetallumNative.swift"


def _mask_noncode(text: str) -> str:
    """Mask comments and strings without changing offsets or line numbers."""

    # Keeping offsets stable lets diagnostics point into the original source.
    pattern = re.compile(
        r"/\*.*?\*/|//[^\n]*|\"\"\".*?\"\"\"|\"(?:\\.|[^\"\\])*\"",
        re.S,
    )
    return pattern.sub(lambda m: "".join("\n" if c == "\n" else " " for c in m.group()), text)


def _body(text: str, signature: str) -> str | None:
    """Return one balanced-brace function body, selected by exact signature."""

    masked = _mask_noncode(text)
    start = masked.find(signature)
    if start < 0:
        return None
    opening = masked.find("{", start)
    if opening < 0:
        return None
    depth = 0
    for index in range(opening, len(masked)):
        if masked[index] == "{":
            depth += 1
        elif masked[index] == "}":
            depth -= 1
            if depth == 0:
                return masked[opening + 1 : index]
    return None


def _count(text: str, pattern: str) -> int:
    return len(re.findall(pattern, _mask_noncode(text)))


def _contains_in_order(text: str, needles: Iterable[str]) -> bool:
    position = 0
    for needle in needles:
        position = text.find(needle, position)
        if position < 0:
            return False
        position += len(needle)
    return True


def _check_factory_overload(count_source: str, signature: str, backend: str) -> list[str]:
    errors: list[str] = []
    body = _body(count_source, signature)
    if body is None:
        return [f"missing tracked {backend} render factory overload"]
    required = (
        "AttachmentActionLedger.shared.enabledFastPathValue()",
        "AttachmentActionLedger.shared.factoryAttempt(commandBuffer, descriptor)",
        "AttachmentActionLedger.shared.result(attachmentToken, encoder: encoder as AnyObject?)",
    )
    for needle in required:
        if needle not in body:
            errors.append(f"tracked {backend} render factory lacks {needle}")
    if "commandBuffer.makeRenderCommandEncoder(descriptor: descriptor)" not in body:
        errors.append(f"tracked {backend} render factory has no underlying render factory call")
    if not _contains_in_order(
        body,
        (
            "factoryAttempt(commandBuffer, descriptor)",
            "commandBuffer.makeRenderCommandEncoder(descriptor: descriptor)",
            "result(attachmentToken, encoder: encoder as AnyObject?)",
        ),
    ):
        errors.append(f"tracked {backend} render factory hook order is not attempt/factory/result")
    return errors


def _check_render_end(count_source: str, signature: str, expected_encoder: str) -> list[str]:
    errors: list[str] = []
    body = _body(count_source, signature)
    if body is None:
        return [f"missing {expected_encoder} render end overload"]
    expected_end = f"AttachmentActionLedger.shared.end({expected_encoder})"
    if expected_end not in body:
        errors.append(f"{expected_encoder} render end does not close AttachmentActionLedger")
    if "encoder.endEncoding()" not in body:
        errors.append(f"{expected_encoder} render end has no Metal endEncoding call")
    if not _contains_in_order(body, (expected_end, "encoderCountRecordEnd(encoder)", "encoder.endEncoding()")):
        errors.append(f"{expected_encoder} render end hook order is not ledger/count/endEncoding")
    return errors


def _check_store_setter(native_source: str, signature: str, ledger_call: str, raw_call: str, label: str) -> list[str]:
    errors: list[str] = []
    body = _body(native_source, signature)
    if body is None:
        return [f"missing {label} store setter ABI"]
    ledger_count = body.count(ledger_call)
    raw_count = body.count(raw_call)
    if ledger_count != 2:
        errors.append(f"{label} store setter has {ledger_count} ledger updates; expected one for M3 and one for M4")
    if raw_count != 2:
        errors.append(f"{label} store setter has {raw_count} Metal updates; expected one for M3 and one for M4")
    if "metal4RenderBridge" not in body or "metal3RenderEncoder" not in body:
        errors.append(f"{label} store setter does not visibly cover both M3 and M4 branches")
    # Each branch must update the ledger before mutating Metal's action.
    if ledger_count == 2 and raw_count == 2 and not _contains_in_order(
        body,
        (ledger_call, raw_call, ledger_call, raw_call),
    ):
        errors.append(f"{label} store setter does not update ledger before each Metal setter")
    return errors


def _enclosing_function_names(text: str, needle: str) -> list[str]:
    masked = _mask_noncode(text)
    names: list[str] = []
    for match in re.finditer(re.escape(needle), masked):
        prefix = masked[: match.start()]
        functions = list(re.finditer(r"\bfunc\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", prefix))
        names.append(functions[-1].group(1) if functions else "<unknown>")
    return names


def check_sources(ledger_source: str, count_source: str, native_source: str) -> list[str]:
    """Return structural failures; an empty list is a pass."""

    errors: list[str] = []
    if "final class AttachmentActionLedger" not in _mask_noncode(ledger_source):
        errors.append("AttachmentActionLedger.swift has no AttachmentActionLedger class")
    for method in ("factoryAttempt", "result", "setDepthStoreAction", "setColorStoreAction", "end"):
        if not re.search(rf"\bfunc\s+{re.escape(method)}\s*\(", _mask_noncode(ledger_source)):
            errors.append(f"AttachmentActionLedger.swift lacks {method} API")

    errors.extend(_check_factory_overload(
        count_source,
        "func encoderCountMakeRender(_ commandBuffer: MTLCommandBuffer, descriptor: MTLRenderPassDescriptor)",
        "Metal 3",
    ))
    errors.extend(_check_factory_overload(
        count_source,
        "func encoderCountMakeRender(_ commandBuffer: MTL4CommandBuffer, descriptor: MTL4RenderPassDescriptor)",
        "Metal 4",
    ))

    errors.extend(_check_store_setter(
        native_source,
        "public func metallum_MTLRenderCommandEncoder_setDepthStoreAction(",
        "AttachmentActionLedger.shared.setDepthStoreAction",
        "encoder.setDepthStoreAction",
        "depth",
    ))
    errors.extend(_check_store_setter(
        native_source,
        "public func metallum_MTLRenderCommandEncoder_setColorStoreAction(",
        "AttachmentActionLedger.shared.setColorStoreAction",
        "encoder.setColorStoreAction",
        "color",
    ))

    errors.extend(_check_render_end(
        count_source,
        "func encoderCountEnd(_ encoder: MTLCommandEncoder)",
        "renderEncoder",
    ))
    errors.extend(_check_render_end(
        count_source,
        "func encoderCountEnd(_ encoder: MTL4RenderCommandEncoder)",
        "encoder",
    ))

    # These are the only intentionally untracked render factories: the two
    # private MTL4 frame-generation copy/composite helpers.  A new bypass must
    # fail this checker instead of silently becoming part of the exclusion.
    untracked_calls = list(re.finditer(r"\bencoderCountMakeRenderUntracked\s*\(", _mask_noncode(native_source)))
    if len(untracked_calls) != 2:
        errors.append(f"expected exactly two untracked render call sites, found {len(untracked_calls)}")
    else:
        names = _enclosing_function_names(native_source, "encoderCountMakeRenderUntracked")
        if sorted(names) != ["encodeComposite", "encodeCopy"]:
            errors.append(f"untracked render exclusion is not limited to encodeCopy/encodeComposite: {names}")
        for name in ("encodeCopy", "encodeComposite"):
            body = _body(native_source, f"func {name}(")
            if body is None or "encoderCountEndUntracked(encoder)" not in body:
                errors.append(f"untracked render helper {name} lacks its explicit untracked end")

    # Raw render encoder creation is allowed only in EncoderCountLedger.swift,
    # where the tracked and explicit-untracked wrappers are defined.
    raw_render = re.compile(r"\.\s*makeRenderCommandEncoder\s*\(")
    for path in sorted(NATIVE.rglob("*.swift")):
        if path == COUNT:
            continue
        source = path.read_text()
        if raw_render.search(_mask_noncode(source)):
            errors.append(f"raw render factory bypass outside EncoderCountLedger.swift: {path.relative_to(ROOT)}")

    # There must remain exactly two tracked overload definitions.  The
    # deliberately untracked helper has a different name and is checked above.
    definitions = re.findall(r"\bfunc\s+encoderCountMakeRender\s*\(", _mask_noncode(count_source))
    if len(definitions) != 2:
        errors.append(f"unexpected encoderCountMakeRender definition count: {len(definitions)}")

    return errors


def _load_sources() -> tuple[str, str, str]:
    return LEDGER.read_text(), COUNT.read_text(), METALLUM.read_text()


def run_self_test() -> None:
    ledger, count, native = _load_sources()
    baseline = check_sources(ledger, count, native)
    if baseline:
        raise AssertionError("baseline source check failed: " + "; ".join(baseline))

    def must_fail(label: str, mutated_ledger: str = ledger, mutated_count: str = count, mutated_native: str = native) -> None:
        failures = check_sources(mutated_ledger, mutated_count, mutated_native)
        if not failures:
            raise AssertionError(f"mutation {label!r} unexpectedly passed")

    must_fail(
        "missing M3 factory attempt",
        mutated_count=count.replace(
            "AttachmentActionLedger.shared.factoryAttempt(commandBuffer, descriptor)", "", 1
        ),
    )
    must_fail(
        "missing M4 factory result",
        mutated_count=count.replace(
            "AttachmentActionLedger.shared.result(attachmentToken, encoder: encoder as AnyObject?)", "", 2
        ),
    )
    must_fail(
        "missing color store hook",
        mutated_native=native.replace("AttachmentActionLedger.shared.setColorStoreAction", "", 1),
    )
    must_fail(
        "missing generic render end hook",
        mutated_count=count.replace("AttachmentActionLedger.shared.end(renderEncoder)", "", 1),
    )
    must_fail(
        "new untracked render bypass",
        mutated_native=native + "\nencoderCountMakeRenderUntracked(commandBuffer, descriptor: descriptor)\n",
    )
    print("Native attachment coverage self-test: PASS (mutation backstop; structural only)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="run in-memory hook deletion/bypass mutations")
    args = parser.parse_args()
    if args.self_test:
        run_self_test()
        return 0
    failures = check_sources(*_load_sources())
    if failures:
        print("Native attachment descriptor/final-action source coverage: FAIL")
        for failure in failures:
            print(f"- {failure}")
        return 1
    print("Native attachment descriptor/final-action source coverage: PASS (structural only; no runtime/semantic proof)")
    print("Untracked render exclusion: encodeCopy and encodeComposite only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
