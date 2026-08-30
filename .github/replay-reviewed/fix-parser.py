#!/usr/bin/env python3
from pathlib import Path

path = Path("scripts/agent/replay_reviewed_codex.py")
text = path.read_text(encoding="utf-8")

# OpenAI apply_patch tolerates indentation drift in context lines. Keep exact
# matching first, then use indentation-insensitive matching only as fallback.
old_find = '''def find_subsequence(haystack, needle, start=0):
    if not needle:
        return start
    limit = len(haystack) - len(needle)
    for index in range(start, limit + 1):
        if haystack[index:index + len(needle)] == needle:
            return index
    return -1
'''
new_find = '''def find_subsequence(haystack, needle, start=0):
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
if old_find not in text:
    raise SystemExit("could not locate find_subsequence helper")
text = text.replace(old_find, new_find, 1)

# The uploaded rollout contains exactly one malformed custom apply_patch call:
# a newly-added Python/shell heredoc block in run_unified_eval_cycle.sh lost
# unified-diff '+' prefixes from `prefix = key + "="` through the closing `}`.
# Normalize only an exact raw signature line. Later normal patches may mention
# the same source line as prefixed context and must remain untouched.
normalizer = '''def normalize_rollout_patch(patch):
    signature = 'prefix = key + "="'
    lines = patch.splitlines()
    matches = [i for i, line in enumerate(lines) if line == signature]
    if not matches:
        return patch
    if len(matches) != 1:
        fail(f"malformed rollout patch signature count: {len(matches)}")
    start = matches[0]
    end = None
    for i in range(start + 1, len(lines) - 1):
        if lines[i] == "+" and lines[i + 1] == "+stage_eval_shader_pack":
            end = i
            break
    if end is None:
        fail("malformed rollout patch end marker missing")
    for i in range(start, end):
        lines[i] = "+" + lines[i]
    return "\\n".join(lines) + ("\\n" if patch.endswith("\\n") else "")

'''
marker = 'def parse_operations(patch):\n'
if marker not in text:
    raise SystemExit("could not locate parse_operations helper")
text = text.replace(marker, normalizer + marker, 1)

old_apply_patch = '''def apply_patch(patch):
    for kind, relative, body in parse_operations(patch):
'''
new_apply_patch = '''def apply_patch(patch):
    patch = normalize_rollout_patch(patch)
    for kind, relative, body in parse_operations(patch):
'''
if old_apply_patch not in text:
    raise SystemExit("could not locate apply_patch helper")
text = text.replace(old_apply_patch, new_apply_patch, 1)

# Preserve actual repository context when indentation-insensitive matching was
# required. Otherwise fuzzy matching could rewrite unchanged context merely to
# the rollout's whitespace rather than changing only +/- lines.
start = text.find('def apply_update(path, body):\n')
end = text.find('\ndef apply_add(path, body):\n', start)
if start < 0 or end < 0:
    raise SystemExit("could not locate apply_update helper")
new_apply_update = '''def apply_update(path, body):
    text = path.read_text(encoding="utf-8")
    had_final_newline = text.endswith("\\n")
    lines = text.splitlines()
    hunks = []
    current = None
    for line in body:
        if line.startswith("@@"):
            if current is not None:
                hunks.append(current)
            current = []
        else:
            if current is None:
                fail(f"update for {path} has content before first hunk")
            current.append(line)
    if current is not None:
        hunks.append(current)

    cursor = 0
    for hunk in hunks:
        old = []
        actions = []
        for line in hunk:
            if not line:
                fail(f"malformed empty patch line for {path}")
            prefix, value = line[0], line[1:]
            if prefix == " ":
                old.append(value)
                actions.append((prefix, value))
            elif prefix == "-":
                old.append(value)
                actions.append((prefix, value))
            elif prefix == "+":
                actions.append((prefix, value))
            elif line == "\\\\ No newline at end of file":
                continue
            else:
                fail(f"unsupported patch line for {path}: {line!r}")
        index = find_subsequence(lines, old, cursor)
        if index < 0:
            index = find_subsequence(lines, old, 0)
        if index < 0:
            preview = "\\n".join(old[:12])
            fail(f"could not locate hunk in {path}:\\n{preview}")

        replacement = []
        old_offset = 0
        for prefix, value in actions:
            if prefix == " ":
                replacement.append(lines[index + old_offset])
                old_offset += 1
            elif prefix == "-":
                old_offset += 1
            else:
                replacement.append(value)
        lines[index:index + len(old)] = replacement
        cursor = index + len(replacement)

    path.write_text("\\n".join(lines) + ("\\n" if had_final_newline else ""), encoding="utf-8")
'''
text = text[:start] + new_apply_update + text[end:]

path.write_text(text, encoding="utf-8")
