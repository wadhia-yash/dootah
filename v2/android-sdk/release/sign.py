#!/usr/bin/env python3
"""Prepare a signed Central bundle locally. There is deliberately no upload support."""
import argparse
import hashlib
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from package import NS, checksums, zip_bytes


def verify_manifest(stage):
    actual = {p.relative_to(stage / 'repository').as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in (stage / 'repository').rglob('*') if p.is_file()
              and p.suffix not in ('.sha256', '.sha512', '.sha1', '.md5')}
    declared = {}
    for line in (stage / 'SHA256SUMS').read_text().splitlines():
        digest, name = line.split('  ', 1)
        if name in declared:
            raise ValueError('Duplicate checksum entry')
        declared[name] = digest
    if actual != declared:
        raise ValueError('Stage bytes no longer match manifest')
    return declared


def export(stage, output, ledger):
    ledger.parent.mkdir(parents=True, exist_ok=True)
    with ledger.with_suffix(ledger.suffix + '.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        export_locked(stage, output, ledger)


def export_locked(stage, output, ledger):
    if output.exists():
        raise ValueError('Refusing to overwrite signed output')
    manifest = verify_manifest(stage)
    audit = json.loads((stage / 'audit.json').read_text())
    if audit['findings']:
        raise ValueError('Content audit did not pass')
    consumer = json.loads((stage / 'consumer-result.json').read_text())
    if consumer.get('stageManifestSha256') != hashlib.sha256((stage / 'SHA256SUMS').read_bytes()).hexdigest():
        raise ValueError('Clean consumer receipt is for different stage bytes')
    if not all(consumer.get(k) is True for k in ['freshGradleHome', 'exclusiveDootahRepository', 'debugD8', 'releaseR8']):
        raise ValueError('Clean consumer acceptance is required')
    for pom in (stage / 'repository').rglob('*.pom'):
        dev = ET.parse(pom).find(f'{{{NS}}}developers/{{{NS}}}developer')
        if dev is None or not dev.findtext(f'{{{NS}}}id') or not dev.findtext(f'{{{NS}}}name'):
            raise ValueError('Set DOOTAH_DEVELOPER_ID and DOOTAH_DEVELOPER_NAME when staging')
    previous = json.loads(ledger.read_text()) if ledger.exists() else {}
    for name, digest in manifest.items():
        if name in previous and previous[name] != digest:
            raise ValueError(f'Immutable coordinate changed: {name}; advance the public version')
    key = os.environ.get('GPG_KEY_ID')
    if not key:
        raise ValueError('GPG_KEY_ID must select your locally configured signing key')
    with tempfile.TemporaryDirectory(prefix='dootah-sign-') as tmp:
        repository = Path(tmp) / 'repository'
        shutil.copytree(stage / 'repository', repository)
        for name in sorted(manifest):
            path = repository / name
            # CI may use a private passphrase file; the value never enters argv or logs.
            password_file = os.environ.get('DOOTAH_GPG_PASSPHRASE_FILE')
            options = []
            if password_file:
                info = Path(password_file).lstat()
                if Path(password_file).is_symlink() or info.st_mode & 0o077:
                    raise ValueError('Signing passphrase file must be private and not a symlink')
                options = ['--batch', '--pinentry-mode', 'loopback', '--passphrase-file', password_file]
            subprocess.run(['gpg', *options, '--local-user', key, '--armor', '--detach-sign', str(path)], check=True)
            subprocess.run(['gpg', '--verify', str(path) + '.asc', str(path)], check=True)
        checksums(repository, Path(tmp) / 'SHA256SUMS')
        data = zip_bytes({p.relative_to(repository).as_posix(): p.read_bytes()
                          for p in repository.rglob('*') if p.is_file()})
        with output.open('xb') as file:
            file.write(data)
    previous.update(manifest)
    ledger.parent.mkdir(parents=True, exist_ok=True)
    temp = ledger.with_suffix('.new')
    temp.write_text(json.dumps(previous, indent=2, sort_keys=True) + '\n')
    temp.replace(ledger)
    print('Signed bundle prepared locally. No upload performed.')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--ledger', type=Path, required=True,
                        help='Retained immutable coordinate checksum ledger; preserve across releases')
    args = parser.parse_args()
    export(args.stage, args.output, args.ledger)
