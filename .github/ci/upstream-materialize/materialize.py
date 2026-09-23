"""Rebuild an audited, hash-pinned candidate; publish objects, never refs."""
import base64
import json
import lzma
import os
from pathlib import Path, PurePosixPath
import subprocess as sp
import sys

workbench = Path.cwd()
out = Path(os.environ['MATERIALIZE_OUTPUT'])
parts = workbench / '.github/ci/upstream-materialize'
manifest = json.loads(lzma.decompress(b''.join((parts / f'part{i}.xz').read_bytes() for i in range(1, 5))))
base, fork = manifest['base'], manifest['fork']
assert base == 'f8294b2fb6ce2edc56d418510294111c9276091e'
assert fork == '790f9cb2a4c7195158e7b2f71a030fcbf8db43e1'

def git(*args, cwd=workbench, data=None):
    return sp.check_output(['git', *args], cwd=cwd, input=data)

def listing(ref, cwd=workbench):
    return {path: tuple(meta.split()) for meta, path in
            (line.split('\t', 1) for line in git('ls-tree', '-r', ref, cwd=cwd).decode().splitlines())}

def target(path):
    p = PurePosixPath(path)
    assert not p.is_absolute() and '..' not in p.parts
    return out / path

def write(path, mode, data):
    p = target(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(data)
    p.chmod(int(mode, 8) & 0o777)

assert not out.exists(), f'refuse to overwrite {out}'
git('cat-file', '-e', base + '^{commit}')
git('cat-file', '-e', fork + '^{commit}')
git('worktree', 'add', '--detach', str(out), base)
b, f = listing(base), listing(fork)
for path, (mode, kind, sha) in f.items():
    if path.endswith('/AGENTS.md'):
        continue
    if any(path == root or path.startswith(root + '/') for root in manifest['copy']):
        assert kind == 'blob' and mode in ('100644', '100755')
        write(path, mode, git('show', sha))
for op in manifest['ops']:
    path = op['path']
    if op.get('delete'):
        target(path).unlink(missing_ok=True)
        continue
    data = op['text'].encode() if 'text' in op else git('show', op['blob'])
    write(path, op['mode'], data)
    if op.get('patch'):
        git('apply', '--whitespace=error', '-', cwd=out, data=op['patch'].encode())
    actual = git('hash-object', path, cwd=out).decode().strip()
    assert actual == op['sha'], (path, actual, op['sha'])
git('add', '-A', cwd=out)
git('diff', '--cached', '--check', cwd=out)
tree = git('write-tree', cwd=out).decode().strip()
assert tree == manifest['tree'], (tree, manifest['tree'])
print('CANDIDATE_TREE_VERIFIED=' + tree, flush=True)
sp.run(['bash', 'scripts/agent/verify_unified_eval.sh'], cwd=out, check=True)
evidence = out / 'build/materialization'
evidence.mkdir(parents=True)
(evidence / 'candidate.patch').write_bytes(git('diff', '--cached', '--binary', base, cwd=out))
(evidence / 'candidate-stat.txt').write_bytes(git('diff', '--cached', '--stat', base, cwd=out))
repo = os.environ['GITHUB_REPOSITORY']
assert repo == '21Z121Z1/MetalUniversal'
assert os.environ['GITHUB_EVENT_NAME'] in ('push', 'workflow_dispatch')
assert os.environ['GITHUB_REF'] == 'refs/heads/agent/upstream-26.3-workbench-20260923'

def post(endpoint, body):
    return json.loads(sp.check_output(['gh', 'api', '--method', 'POST',
        f'repos/{repo}/git/{endpoint}', '--input', '-'], input=json.dumps(body).encode()))

known = {entry[2] for d in (b, f) for entry in d.values()}
desired = listing(tree, cwd=out)
entries = []
for path in sorted(set(b) | set(desired)):
    if b.get(path) == desired.get(path):
        continue
    if path not in desired:
        entries.append(dict(path=path, mode=b[path][0], type='blob', sha=None))
        continue
    mode, kind, sha = desired[path]
    if sha not in known:
        result = post('blobs', dict(encoding='base64', content=base64.b64encode(git('show', sha, cwd=out)).decode()))
        assert result['sha'] == sha, (path, result)
        known.add(sha)
    entries.append(dict(path=path, mode=mode, type=kind, sha=sha))
base_tree = git('rev-parse', base + '^{tree}').decode().strip()
result = post('trees', dict(base_tree=base_tree, tree=entries))
assert result['sha'] == tree, result
message = '''Port Minecraft 26.3 RenderPearl and exact-artifact validation onto upstream

Base: EternityQwQ/MetalUniversal 26.3-fabric-dev f8294b2fb6ce2edc56d418510294111c9276091e
Migration origin: 825226f2e747ec557629866232e5b55c99d34a2e
Source reviewed through: 790f9cb2a4c7195158e7b2f71a030fcbf8db43e1

Preserve upstream toolchain/version pins and defensive texture ownership.
Separate Vanilla, Sodium-only and optional Iris runtime profiles. Migrate
hosted offscreen Metal/E2E and physical P1 correctness/performance closure;
physical trials load the exact production JAR and verify native identity.
Do not import fork history, repository reports or workbench automation.
Physical validation procedure migrated; physical hardware not validated.'''
result = post('commits', dict(message=message, tree=tree, parents=[base]))
receipt = dict(head=result['sha'], tree=tree, base=base, fork=fork, changed_files=len(entries))
(evidence / 'candidate.json').write_text(json.dumps(receipt, indent=2) + '\n')
print('CANDIDATE_READY=' + json.dumps(receipt), flush=True)
