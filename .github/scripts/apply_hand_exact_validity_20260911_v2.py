from pathlib import Path

script_path = Path('.github/scripts/apply_hand_exact_validity_20260911.py')
source = script_path.read_text()

# V1 intentionally uses exact anchors, but this parameter pair appears in
# multiple native helpers. Narrow only this one edit to the function signature;
# keep the one-match requirement rather than accepting an ambiguous replacement.
old_anchor = '''    """    _ objectValidityTexture: MTLTexture,\n    _ reactiveTexture: MTLTexture,\n""",\n    """    _ objectValidityTexture: MTLTexture,\n    _ handExactValidityTexture: MTLTexture?,\n    _ reactiveTexture: MTLTexture,\n""",\n    "metal3 hand overlay parameter",\n'''
new_anchor = '''    """private func metal3MetalFxEncodeHandOverlay(\n    _ commandBuffer: MTLCommandBuffer,\n    _ handDepthTexture: MTLTexture,\n    _ objectMotionTexture: MTLTexture,\n    _ objectValidityTexture: MTLTexture,\n    _ reactiveTexture: MTLTexture,\n""",\n    """private func metal3MetalFxEncodeHandOverlay(\n    _ commandBuffer: MTLCommandBuffer,\n    _ handDepthTexture: MTLTexture,\n    _ objectMotionTexture: MTLTexture,\n    _ objectValidityTexture: MTLTexture,\n    _ handExactValidityTexture: MTLTexture?,\n    _ reactiveTexture: MTLTexture,\n""",\n    "metal3 hand overlay parameter",\n'''
if source.count(old_anchor) != 1:
    raise SystemExit(f'expected one hand-overlay executor anchor, found {source.count(old_anchor)}')
source = source.replace(old_anchor, new_anchor, 1)

start_marker = '''# Public V1 Swift wrapper calls the old C entry and therefore remains ABI-compatible.\n# The internal Metal 3 call introduced above needs V1 to pass nil.\nswift = replace_once(\n'''
end_marker = '''    "legacy hand-overlay fallback nil mask",\n)\n\n# Fused shader consumes a dedicated validity texture. World validity is never\n'''
start = source.find(start_marker)
if start < 0:
    raise SystemExit('missing stale hand-overlay fallback block start')
end = source.find(end_marker, start)
if end < 0:
    raise SystemExit('missing stale hand-overlay fallback block end')
replacement = '''# Public V1 Swift wrapper calls the old C entry and therefore remains ABI-compatible.\n# Fused shader consumes a dedicated validity texture. World validity is never\n'''
source = source[:start] + replacement + source[end + len(end_marker):]
exec(compile(source, str(script_path), 'exec'), {'__name__': '__main__', '__file__': str(script_path)})
