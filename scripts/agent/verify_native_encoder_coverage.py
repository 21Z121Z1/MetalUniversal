#!/usr/bin/env python3
"""Structural backstop: native encoder factories/end calls must use the ledger wrappers.

This does not prove runtime coverage, ownership, or GPU correctness. Physical tests
and per-submission lifecycle reconciliation provide those independent obligations.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "src/main/native"
WRAPPERS = NATIVE / "EncoderCountLedger.swift"
RAW = re.compile(r"\.\s*(?:make(?:Render|Blit|Compute)CommandEncoder|endEncoding)\s*\(")


def code_only(text: str) -> str:
    # Keep line boundaries so a failure points at the actual source location.
    return re.sub(r'/\*.*?\*/|//[^\n]*|""".*?"""|"(?:\\.|[^"\\])*"',
                  lambda match: "\n" * match[0].count("\n"), text, flags=re.S)


def main() -> None:
    assert RAW.search(code_only("buffer .\n makeRenderCommandEncoder(descriptor: p)"))
    assert not RAW.search(code_only('// buffer.endEncoding()\n"a.makeBlitCommandEncoder()"'))
    failures = []
    for path in sorted(NATIVE.rglob("*.swift")):
        if path == WRAPPERS:
            continue
        text = code_only(path.read_text())
        for match in RAW.finditer(text):
            failures.append(f"{path.relative_to(ROOT)}:{text.count(chr(10), 0, match.start()) + 1}")
    if not WRAPPERS.is_file() or not RAW.search(code_only(WRAPPERS.read_text())):
        failures.append("native encoder wrapper implementation is missing")
    if failures:
        raise SystemExit("Native encoder operations bypass ledger wrappers: " + ", ".join(failures))
    print("Native encoder factory/end source coverage: PASS (structural only)")


if __name__ == "__main__":
    main()
