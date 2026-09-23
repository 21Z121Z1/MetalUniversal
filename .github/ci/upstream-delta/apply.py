"""Verify an explicit candidate delta and store blobs; never move a branch."""
import base64, json, os, subprocess as sp
from pathlib import Path
assert os.environ['GITHUB_REPOSITORY'] == '21Z121Z1/MetalUniversal'
assert os.environ['GITHUB_REF'] == 'refs/heads/agent/upstream-26.3-workbench-20260923'
assert os.environ['GITHUB_EVENT_NAME'] in ('push', 'workflow_dispatch')
base = os.environ['CANDIDATE_BASE']
expected = os.environ['CANDIDATE_TREE']
def git(*args): return sp.check_output(['git', *args])
assert git('rev-parse', 'HEAD').decode().strip() == base
git('apply', '--index', '--whitespace=error', os.environ['DELTA_FILE'])
git('diff', '--cached', '--check')
tree = git('write-tree').decode().strip()
assert tree == expected, (tree, expected)
sp.run(['bash', 'scripts/agent/verify_unified_eval.sh'], check=True)
entries = []
for path in git('diff', '--cached', '--name-only').decode().splitlines():
    p = Path(path)
    if not p.is_file():
        entries.append(dict(path=path, mode='100644', type='blob', sha=None))
        continue
    mode, sha, _ = git('ls-files', '-s', path).decode().split('\t')[0].split()
    payload = dict(encoding='base64', content=base64.b64encode(p.read_bytes()).decode())
    result = json.loads(sp.check_output(['gh','api','--method','POST',
        'repos/21Z121Z1/MetalUniversal/git/blobs','--input','-'],input=json.dumps(payload).encode()))
    assert result['sha'] == sha, (path, result)
    entries.append(dict(path=path, mode=mode, type='blob', sha=sha))
receipt = dict(base=base, base_tree=git('rev-parse', base+'^{tree}').decode().strip(), tree=tree, entries=entries)
out = Path(os.environ['DELTA_RECEIPT']); out.mkdir(parents=True,exist_ok=True)
(out/'delta.json').write_text(json.dumps(receipt,indent=2)+'\n')
(out/'delta.patch').write_bytes(git('diff','--cached','--binary'))
print('DELTA_READY='+json.dumps(receipt),flush=True)
