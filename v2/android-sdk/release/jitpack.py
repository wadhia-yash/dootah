#!/usr/bin/env python3
"""Re-coordinate a validated release stage for JitPack.

JitPack serves a repository as com.github.OWNER.REPO:MODULE:TAG. This maps every Dootah
group (dev.dootah, dev.dootah.internal) to that group and every Dootah version to the tag.
It rewrites POMs, Gradle module metadata and checksums. Archive bytes are unchanged; the
plugin's runtime module must already have been set at build time (dootahRuntimeModule).

usage: jitpack.py STAGE GROUP TAG OUTPUT.zip
"""
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

DOOTAH_GROUPS = ('dev.dootah', 'dev.dootah.internal')


def main(stage, group, tag, output):
    repo = Path(stage) / 'repository'
    if not repo.is_dir():
        sys.exit('missing stage repository')
    output = Path(output)
    if output.exists():
        sys.exit('refusing existing output')
    modules = {}  # (old group, artifact) -> old version
    for pom in repo.rglob('*.pom'):
        version_dir = pom.parent
        artifact, old_group = version_dir.parent.name, '.'.join(version_dir.parent.parent.relative_to(repo).parts)
        if old_group not in DOOTAH_GROUPS:
            sys.exit(f'unexpected group {old_group}')
        if (old_group, artifact) in modules:
            sys.exit(f'multiple versions of {old_group}:{artifact}')
        modules[(old_group, artifact)] = version_dir.name
    artifacts = [a for _, a in modules]
    if len(set(artifacts)) != len(artifacts):
        sys.exit('artifact id collision after re-coordination')
    versions = {(g, a): v for (g, a), v in modules.items()}

    def new_version(old_group, artifact):
        return tag if (old_group, artifact) in versions else None

    def pom_text(text):
        def dependency(m):
            block = m.group(0)
            g = re.search(r'<groupId>([^<]+)</groupId>', block)
            a = re.search(r'<artifactId>([^<]+)</artifactId>', block)
            if g and a and g.group(1) in DOOTAH_GROUPS:
                if (g.group(1), a.group(1)) not in versions:
                    sys.exit(f'dependency on unstaged {g.group(1)}:{a.group(1)}')
                block = block.replace(f'<groupId>{g.group(1)}</groupId>', f'<groupId>{group}</groupId>', 1)
                block = re.sub(r'<version>[^<]+</version>', f'<version>{tag}</version>', block, count=1)
            return block
        text = re.sub(r'<dependency>.*?</dependency>', dependency, text, flags=re.S)
        head, sep, rest = text.partition('<dependencies>')
        for old in DOOTAH_GROUPS:
            head = head.replace(f'<groupId>{old}</groupId>', f'<groupId>{group}</groupId>', 1) if f'<groupId>{old}</groupId>' in head else head
        head = re.sub(r'(</artifactId>\s*<version>)[^<]+(</version>)', lambda m: m.group(1) + tag + m.group(2), head, count=1)
        return head + sep + rest

    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as out:
        def put(path, data):
            out.writestr(path, data)
            for algo in ('md5', 'sha1', 'sha256', 'sha512'):
                out.writestr(f'{path}.{algo}', hashlib.new(algo, data).hexdigest())
        for (old_group, artifact), old_version in sorted(modules.items()):
            source = repo.joinpath(*old_group.split('.'), artifact, old_version)
            target = '/'.join(group.split('.') + [artifact, tag])
            renamed = {}
            for f in sorted(source.iterdir()):
                if f.suffix in ('.md5', '.sha1', '.sha256', '.sha512', '.asc'):
                    continue
                if not f.name.startswith(f'{artifact}-{old_version}'):
                    sys.exit(f'unexpected file {f}')
                name = f'{artifact}-{tag}' + f.name[len(f'{artifact}-{old_version}'):]
                renamed[f.name] = name
                data = f.read_bytes()
                if f.suffix == '.pom':
                    data = pom_text(data.decode()).encode()
                elif f.suffix == '.module':
                    continue
                put(f'{target}/{name}', data)
            module = source / f'{artifact}-{old_version}.module'
            if module.exists():
                meta = json.loads(module.read_text())
                meta['component'].update(group=group, version=tag)
                for variant in meta.get('variants', []):
                    for dep in variant.get('dependencies', []):
                        if dep['group'] in DOOTAH_GROUPS:
                            if (dep['group'], dep['module']) not in versions:
                                sys.exit(f"dependency on unstaged {dep['group']}:{dep['module']}")
                            dep['group'] = group
                            dep['version'] = {'requires': tag}
                    for file in variant.get('files', []):
                        file['name'] = file['url'] = renamed[file['name']]
                    if 'available-at' in variant:
                        sys.exit('available-at variants are not expected')
                put(f'{target}/{artifact}-{tag}.module', (json.dumps(meta, indent=2) + '\n').encode())
    print(f'{len(modules)} modules re-coordinated to {group}:*:{tag}')


if __name__ == '__main__':
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])
