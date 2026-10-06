"""Audit go list -deps -json output and source inventory; fail on EE or router reachability."""
import hashlib
import json
import pathlib
import sys
import subprocess

if len(sys.argv) != 3:
    sys.exit('usage: audit.py prepared-source-directory upstream-checkout')
root = pathlib.Path(sys.argv[1]).resolve()
decoder = json.JSONDecoder()
def decode(path):
    raw = path.read_text()
    result = []
    while raw.strip():
        item, end = decoder.raw_decode(raw.lstrip())
        raw = raw.lstrip()[end:]
        result.append(item)
    return result

packages = decode(root / 'deps.json')
for p in decode(root / 'test-deps.json'):
    assert not p.get('Error') and not p.get('DepsErrors'), p
    assert '/ee/' not in p['ImportPath'] and 'xprem/internal/router' not in p['ImportPath'], p['ImportPath']
    assert all('/ee/' not in dep and dep != 'xprem/internal/router' for dep in p.get('Imports', []))
assert not list(root.rglob('ee')), 'EE build input present'
assert not (root / 'internal/router').exists(), 'mixed-license router present'
upstream = pathlib.Path(sys.argv[2]).resolve()
upstream_files = 0
assert subprocess.check_output(['git', '-C', str(upstream), 'rev-parse', 'HEAD'], text=True).strip() == 'b46e13569f5734a66ed903f75f51b87b78e80c1b'
assert not subprocess.check_output(['git', '-C', str(upstream), 'status', '--porcelain'], text=True).strip()
for directory in ('config', 'internal'):
    for path in (root / directory).rglob('*'):
        if path.is_file():
            assert path.read_bytes() == (upstream / path.relative_to(root)).read_bytes(), path
            upstream_files += 1
assert upstream_files > 0, 'no build input compared with the upstream checkout'
modules = {}
source_count = 0
for p in packages:
    assert not p.get('Error') and not p.get('DepsErrors'), p
    assert '/ee/' not in p['ImportPath'] and p['ImportPath'] != 'xprem/internal/router', p['ImportPath']
    for dependency in p.get('Imports', []):
        assert '/ee/' not in dependency and dependency != 'xprem/internal/router', dependency
    for field in ('GoFiles', 'CgoFiles', 'CFiles', 'CXXFiles', 'SFiles', 'EmbedFiles'):
        for name in p.get(field, []):
            assert 'ee' not in pathlib.PurePosixPath(name).parts, name
            source_count += 1
    m = p.get('Module', {})
    if m.get('Version'):
        assert not m.get('Replace'), m
        modules[m['Path']] = {'version': m['Version'], 'sum': m.get('Sum')}
print(json.dumps({'packages': len(packages), 'sourceFiles': source_count, 'eeDependencies': [],
                  'unchangedUpstreamFilesVerified': upstream_files,
                  'goModSHA256': hashlib.sha256((root / 'go.mod').read_bytes()).hexdigest(),
                  'goSumSHA256': hashlib.sha256((root / 'go.sum').read_bytes()).hexdigest(),
                  'modules': modules}, indent=2, sort_keys=True))
