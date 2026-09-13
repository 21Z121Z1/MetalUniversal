#!/usr/bin/env python3
from pathlib import Path

path = Path('scripts/agent/apply-metalfx-color-transfer-foundation-20260913.py')
source = path.read_text()
old = '''def replace_once(path: str, old: str, new: str) -> None:\n    p = Path(path)\n    text = p.read_text()\n    count = text.count(old)\n    if count != 1:\n        raise SystemExit(f"{path}: expected exactly one anchor, found {count}: {old[:120]!r}")\n    p.write_text(text.replace(old, new, 1))\n'''
new = '''def replace_once(path: str, old: str, new: str) -> None:\n    p = Path(path)\n    text = p.read_text()\n    count = text.count(old)\n    ambiguous_make_texture_set_signature = (\n        "        outputFormat: MTLPixelFormat,\\n"\n        "        depthFormat: MTLPixelFormat,\\n"\n        "        motionFormat: MTLPixelFormat,\\n"\n        "        depthWidth: Int,"\n    )\n    if count == 2 and old == ambiguous_make_texture_set_signature:\n        # makeTextureSet precedes rebuildTextures in MetallumNative.swift; this\n        # call intentionally edits only that first declaration. All other\n        # anchors remain exactly-one assertions.\n        p.write_text(text.replace(old, new, 1))\n        return\n    if count != 1:\n        raise SystemExit(f"{path}: expected exactly one anchor, found {count}: {old[:120]!r}")\n    p.write_text(text.replace(old, new, 1))\n'''
if source.count(old) != 1:
    raise SystemExit(f'expected one replace_once helper, got {source.count(old)}')
fixed = source.replace(old, new, 1)
compile(fixed, str(path), 'exec')
exec(compile(fixed, str(path), 'exec'), {'__name__': '__main__', '__file__': str(path)})
