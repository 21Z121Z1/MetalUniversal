#!/usr/bin/env python3
from pathlib import Path
import re

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
# body. Keep this compatibility narrow: an otherwise unsupported line inside
# an already parsed hunk is an insertion; context and deletion matching remain
# unchanged.
text, raw_parse_count = re.subn(
    r'(?m)^(?P<indent>[ \t]*)raise SystemExit\(f"unsupported patch line for \{path\}: \{patch_line!r\}"\)\s*$',
    lambda match: match.group("indent") + "new.append(patch_line)",
    text,
)
if raw_parse_count != 1:
    raise SystemExit(f"expected one unsupported-line parser branch, found {raw_parse_count}")

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
