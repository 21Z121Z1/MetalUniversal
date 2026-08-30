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

lines = text.splitlines()
raw_rejections = [i for i, line in enumerate(lines) if "unsupported patch line for" in line]
if len(raw_rejections) != 1:
    raise SystemExit(f"expected one unsupported-line parser branch, found {len(raw_rejections)}")
raw_rejection = raw_rejections[0]

# Anchor to the actual parser rejection site and find the nearest enclosing
# loop that iterates patch_line. This avoids depending on the helper's local
# variable name (hunk/hunk_lines/etc.).
loop = None
for i in range(raw_rejection - 1, -1, -1):
    stripped = lines[i].strip()
    if stripped.startswith("for patch_line in ") and stripped.endswith(":"):
        loop = i
        break
if loop is None:
    raise SystemExit("could not locate patch_line parser loop above rejection site")
loop_indent = lines[loop][:len(lines[loop]) - len(lines[loop].lstrip())]
body_indent = loop_indent + "    "
lines.insert(loop, loop_indent + "raw_addition_mode = False")
loop += 1
raw_guard = [
    body_indent + "if raw_addition_mode:",
    body_indent + "    if patch_line.startswith('+'):",
    body_indent + "        raw_addition_mode = False",
    body_indent + "    else:",
    body_indent + "        new.append(patch_line)",
    body_indent + "        continue",
]
for offset, line in enumerate(raw_guard, 1):
    lines.insert(loop + offset, line)

# Recompute rejection positions after insertion.
raw_rejections = [i for i, line in enumerate(lines) if "unsupported patch line for" in line]
index = raw_rejections[0]
indent = lines[index][:len(lines[index]) - len(lines[index].lstrip())]
lines[index:index + 1] = [indent + "raw_addition_mode = True", indent + "new.append(patch_line)"]

empty_rejections = [i for i, line in enumerate(lines) if "malformed empty patch line for" in line]
if len(empty_rejections) != 1:
    raise SystemExit(f"expected one empty-line parser branch, found {len(empty_rejections)}")
index = empty_rejections[0]
indent = lines[index][:len(lines[index]) - len(lines[index].lstrip())]
lines[index:index + 1] = [indent + "raw_addition_mode = True", indent + 'new.append("")', indent + "continue"]
text = "\n".join(lines) + "\n"

old_write = '''        lines[index:index + len(old)] = new
        cursor = index + len(new)
'''
new_write = '''        replacement = []
        old_offset = 0
        raw_addition_mode = False
        for patch_line in hunk:
            if raw_addition_mode:
                if patch_line.startswith("+"):
                    raw_addition_mode = False
                else:
                    replacement.append(patch_line)
                    continue
            if patch_line == "\\\\ No newline at end of file":
                continue
            if patch_line == "":
                raw_addition_mode = True
                replacement.append("")
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
                raw_addition_mode = True
                replacement.append(patch_line)
        lines[index:index + len(old)] = replacement
        cursor = index + len(replacement)
'''
if old_write not in text:
    raise SystemExit("failed to locate hunk replacement write")
text = text.replace(old_write, new_write, 1)

path.write_text(text, encoding="utf-8")
