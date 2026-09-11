#!/usr/bin/env python3
from pathlib import Path

path = Path("src/main/native/MetallumNative.swift")
text = path.read_text(encoding="utf-8")
needle = "descriptor.isAutoExposureEnabled = false"
count = text.count(needle)
if count != 2:
    raise SystemExit(f"expected exactly two live Temporal auto-exposure assignments, found {count}")

text = text.replace(needle, "descriptor.isAutoExposureEnabled = true")
if text.count("descriptor.isAutoExposureEnabled = true") < 2:
    raise SystemExit("failed to enable auto exposure on both Temporal descriptors")
path.write_text(text, encoding="utf-8")
print("Enabled MetalFX auto exposure on both Metal 3 and Metal 4 Temporal descriptors")
