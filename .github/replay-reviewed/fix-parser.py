#!/usr/bin/env python3
from pathlib import Path

path = Path("scripts/agent/replay_reviewed_codex.py")
text = path.read_text(encoding="utf-8")
text = text.replace(
'''def find_subsequence(haystack, needle, start=0):
    if not needle:
        return start
    limit = len(haystack) - len(needle)
    for index in range(start, limit + 1):
        if haystack[index:index + len(needle)] == needle:
            return index
    return -1
''',
'''def find_subsequence(haystack, needle, start=0):
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
)
# Codex's custom apply_patch accepts unprefixed lines inside an added heredoc
# body. Keep that compatibility narrow: only an otherwise unsupported line in
# an already parsed hunk is treated as an insertion. Context/deletion matching
# remains unchanged and still has to identify the existing source text.
text = text.replace(
'''        else:
            raise SystemExit(f"unsupported patch line for {path}: {patch_line!r}")
''',
'''        else:
            new.append(patch_line)
'''
)
text = text.replace(
'''        lines[index:index + len(old)] = new
        cursor = index + len(new)
''',
'''        replacement = []
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
)
path.write_text(text, encoding="utf-8")
