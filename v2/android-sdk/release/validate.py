#!/usr/bin/env python3
"""Validate the finalized Maven graph and metadata independently of the packager."""
import json
from pathlib import Path
import sys
import zipfile
from package import INTERNAL, MIRRORS, OWNED, NS, coordinate, read_pom, sha, text
from sign import verify_manifest


def validate(stage):
    verify_manifest(stage)
    repository = stage / 'repository'
    poms = {coordinate(read_pom(p)): (p, read_pom(p)) for p in repository.rglob('*.pom')}
    names = {key[1] for key in poms}
    if names != OWNED | INTERNAL | MIRRORS.keys() or len(poms) != len(names):
        raise ValueError('Unexpected/missing publication')
    notices = json.loads((stage / 'inventory.json').read_text())
    if not notices or any(not x.get('origin') or not (x.get('license') or x.get('licenses')) for x in notices):
        raise ValueError('Incomplete third-party inventory')
    if json.loads((stage / 'audit.json').read_text())['findings']:
        raise ValueError('Content audit failed')
    for coord, (path, pom) in poms.items():
        group, name, version = coord
        expected_group = 'dev.dootah' if name in OWNED else 'dev.dootah.internal'
        if group != expected_group or version.endswith('-local'):
            raise ValueError(f'Invalid release coordinate {coord}')
        if any(not text(pom, key) for key in ('name', 'description', 'url')):
            raise ValueError(f'Missing POM metadata {coord}')
        for tag in ['licenses/license/name', 'scm/connection', 'scm/url']:
            if not pom.findtext('/'.join('{' + NS + '}' + s for s in tag.split('/'))):
                raise ValueError(f'Missing {tag}: {coord}')
        for dep in pom.findall(f'.//{{{NS}}}dependency'):
            target = coordinate(dep)
            if target[2] and any(token in target[2] for token in ['+', 'SNAPSHOT', '[', ']', '(', ')']):
                raise ValueError(f'Nonimmutable dependency version {target}')
            if not all(target):
                raise ValueError(f'Incomplete dependency {target}')
            if target[0].startswith('dev.dootah') and target not in poms:
                raise ValueError(f'Dangling dependency {target}')
            if target[0] in ['host.exp.exponent', 'expo.modules.asset', 'expo.modules.webview']:
                raise ValueError(f'Unrewritten upstream coordinate {target}')
        base = f'{name}-{version}'
        if name != 'dev.dootah.gradle.plugin':
            for suffix in ['-sources.jar', '-javadoc.jar']:
                with zipfile.ZipFile(path.parent / (base + suffix)) as archive:
                    entries = archive.namelist()
                    if 'META-INF/dootah/NOTICE' not in entries or 'META-INF/dootah/inventory.json' not in entries:
                        raise ValueError('Missing notices')
                    if suffix == '-sources.jar' and not any(n.endswith(('.kt', '.java', '.groovy')) for n in entries):
                        raise ValueError('Empty sources')
                    if suffix == '-javadoc.jar' and len(archive.read('index.html')) < 500:
                        raise ValueError('Empty documentation')
        module = path.with_suffix('.module')
        if module.is_file():
            metadata = json.loads(module.read_text())
            if next(iter(metadata)) != 'formatVersion':
                raise ValueError('Gradle metadata formatVersion must be first')
            for variant in metadata['variants']:
                for f in variant.get('files', []):
                    data = (path.parent / f['url']).read_bytes()
                    if sha(data) != f['sha256'] or len(data) != f['size']:
                        raise ValueError('Stale Gradle artifact metadata')
                for dep in variant.get('dependencies', []):
                    if dep['group'].startswith('dev.dootah') and (dep['group'], dep['module'], dep.get('version', {}).get('requires')) not in poms:
                        raise ValueError('Dangling Gradle module dependency')
    print(f'Validated {len(poms)} Maven publications, notices, checksums and dependency metadata.')

if __name__ == '__main__':
    validate(Path(sys.argv[1]).resolve())
