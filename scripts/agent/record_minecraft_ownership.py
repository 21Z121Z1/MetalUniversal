#!/usr/bin/env python3
"""Preserve only exact-version window/render-loop reference files for ownership review."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

NAMES = {"Minecraft.java", "Window.java", "RenderSystem.java", "Timer.java", "DeltaTracker.java",
         "FramerateLimitTracker.java", "SdlWindow.java", "SDLWindow.java"}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    source = args.sources.resolve(strict=True)
    args.output.mkdir(parents=True, exist_ok=True)
    info = source.parent / "REFERENCE_INFO.txt"
    shutil.copyfile(info, args.output / info.name)
    manifest = {"scope": "exact upstream reference, not project-owned code or runtime proof", "files": []}
    for path in sorted(source.rglob("*.java")):
        if path.name not in NAMES:
            continue
        relative = path.relative_to(source)
        target = args.output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        manifest["files"].append({"path": relative.as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    if not any(row["path"].endswith("net/minecraft/client/Minecraft.java") for row in manifest["files"]):
        raise SystemExit("Exact Minecraft client reference is missing")
    (args.output / "reference-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

if __name__ == "__main__":
    main()
