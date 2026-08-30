#!/usr/bin/env python3
from pathlib import Path

path = Path("scripts/agent/replay_reviewed_codex.py")
text = path.read_text(encoding="utf-8")

original = '''def find_subsequence(haystack, needle, start=0):
    if not needle:
        return start
    limit = len(haystack) - len(needle)
    for index in range(start, limit + 1):
        if haystack[index:index + len(needle)] == needle:
            return index
    return -1
'''
replacement = '''def find_subsequence(haystack, needle, start=0):
    if not needle:
        return start
    limit = len(haystack) - len(needle)
    for index in range(start, limit + 1):
        if haystack[index:index + len(needle)] == needle:
            return index
    normalized = [line.lstrip() for line in needle]
    for index in range(start, limit + 1):
        if [line.lstrip() for line in haystack[index:index + len(needle)]] == normalized:
            return index
    return -1
'''
if original in text:
    text = text.replace(original, replacement, 1)
if "normalized = [line.lstrip() for line in needle]" not in text:
    raise SystemExit("failed to install indentation-compatible context lookup")

# Codex's custom apply_patch accepts unprefixed lines inside an added heredoc
# body. Patch the single parser rejection site structurally rather than
# depending on the exact exception type/quoting used by the generated helper.
lines = text.splitlines()
raw_rejections = [i for i, line in enumerate(lines) if "unsupported patch line for" in line]
if len(raw_rejections) != 1:
    raise SystemExit(f"expected one unsupported-line parser branch, found {len(raw_rejections)}")
index = raw_rejections[0]
indent = lines[index][:len(lines[index]) - len(lines[index].lstrip())]
lines[index] = indent + "new.append(patch_line)"
text = "\n".join(lines) + "\n"

old_write = '''        lines[index:index + len(old)] = new
        cursor = index + len(new)
'''
new_write = '''        replacement = []
        old_offset = 0
        for patch_line in hunk:
            if patch_line == "\\\\ No newline at end of file":
                continue
            prefix, value = patch_line[0], patch_line[1:]
            if prefix == " ":
                replacement.append(lines[index + old_offset])
                old_offset += 1
            elif prefix == "-":
                old_offset += 1
            elif prefix == "+":
                replacement.append(value)
            else:
                replacement.append(patch_line)
        lines[index:index + len(old)] = replacement
        cursor = index + len(replacement)
'''
if old_write in text:
    text = text.replace(old_write, new_write, 1)
if "replacement.append(patch_line)" not in text:
    raise SystemExit("failed to install raw heredoc replacement support")

path.write_text(text, encoding="utf-8")
