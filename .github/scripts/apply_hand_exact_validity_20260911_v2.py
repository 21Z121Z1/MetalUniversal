from pathlib import Path

script_path = Path('.github/scripts/apply_hand_exact_validity_20260911.py')
source = script_path.read_text()
start_marker = '''# Public V1 Swift wrapper calls the old C entry and therefore remains ABI-compatible.\n# The internal Metal 3 call introduced above needs V1 to pass nil.\nswift = replace_once(\n'''
end_marker = '''    "legacy hand-overlay fallback nil mask",\n)\n\n# Fused shader consumes a dedicated validity texture. World validity is never\n'''
start = source.find(start_marker)
if start < 0:
    raise SystemExit('missing stale hand-overlay fallback block start')
end = source.find(end_marker, start)
if end < 0:
    raise SystemExit('missing stale hand-overlay fallback block end')
# Preserve the following comment because it documents the next semantic edit.
replacement = '''# Public V1 Swift wrapper calls the old C entry and therefore remains ABI-compatible.\n# Fused shader consumes a dedicated validity texture. World validity is never\n'''
source = source[:start] + replacement + source[end + len(end_marker):]
exec(compile(source, str(script_path), 'exec'), {'__name__': '__main__', '__file__': str(script_path)})
