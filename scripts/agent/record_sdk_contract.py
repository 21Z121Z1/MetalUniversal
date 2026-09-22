#!/usr/bin/env python3
"""Record the installed SDK identity and small API declaration excerpts, not an SDK copy."""
import argparse
from pathlib import Path
import subprocess

DECLARATIONS = {
    "QuartzCore/CAMetalDisplayLink.h": ("targetTimestamp", "targetPresentationTimestamp", "preferredFrameLatency", "preferredFrameRateRange", "didUpdate", "drawable"),
    "QuartzCore/CAMetalLayer.h": ("nextDrawable", "maximumDrawableCount", "displaySyncEnabled", "allowsNextDrawableTimeout", "residencySet"),
    "Metal/MTLDrawable.h": ("presentedTime", "addPresentedHandler", "drawableID", "presentAtTime"),
    "Metal/MTL4CommandQueue.h": ("waitForDrawable", "signalDrawable", "commit:", "addResidencySet"),
    "MetalFX/MTLFXTemporalScaler.h": ("supportsDevice", "supportsMetal4FX", "newTemporalScaler", "reset", "motionVectorScale", "depthReversed"),
    "MetalFX/MTLFXFrameInterpolator.h": ("supportsDevice", "supportsMetal4FX", "newFrameInterpolator", "jitter", "prevColorTexture"),
}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    sdk = Path(subprocess.check_output(["xcrun", "--show-sdk-path"], text=True).strip())
    parts = [subprocess.check_output(["xcodebuild", "-version"], text=True),
             subprocess.check_output(["xcrun", "--show-sdk-version"], text=True), str(sdk)]
    for relative, symbols in DECLARATIONS.items():
        framework, name = relative.split("/")
        header = sdk / "System/Library/Frameworks" / f"{framework}.framework/Headers" / name
        parts.append(f"\n## {relative}")
        if not header.is_file():
            parts.append("unavailable: header absent in this SDK")
            continue
        lines = header.read_text().splitlines()
        selected = set()
        for index, line in enumerate(lines):
            if any(symbol in line for symbol in symbols):
                selected.update(range(max(0, index - 6), min(len(lines), index + 5)))
        for index in sorted(selected):
            parts.append(f"{index + 1}: {lines[index]}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(parts) + "\n")

if __name__ == "__main__":
    main()
