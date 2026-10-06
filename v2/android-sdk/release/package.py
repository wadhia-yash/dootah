#!/usr/bin/env python3
"""Build a local, closed Maven release set. Never uploads or overwrites a candidate."""
import argparse
import hashlib
import html
import io
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

SDK = Path(__file__).resolve().parents[1]
ROOT = SDK.parents[1]
PRODUCER = SDK.parent / 'runtime-spike'
NS = 'http://maven.apache.org/POM/4.0.0'
ET.register_namespace('', NS)
PROJECT_URL = 'https://github.com/wadhia-yash/dootah'
MIRRORS = {'expo.modules.asset': 'expo-asset', 'expo.modules.filesystem': 'expo-file-system',
           'expo.modules.font': 'expo-font', 'expo.modules.keepawake': 'expo-keep-awake',
           'expo.modules.webview': '@expo/dom-webview'}
INTERNAL = {'expo', 'expo-brownfield', 'expo-constants', 'expo-eas-client', 'expo-json-utils',
            'expo-log-box', 'expo-manifests', 'expo-modules-core', 'expo-structured-headers',
            'expo-updates', 'expo-updates-interface'}
OWNED = {'runtime-v2', 'dootah-android-plugin', 'dev.dootah.gradle.plugin'}

def sha(data):
    return hashlib.sha256(data).hexdigest()

def encode(data):
    return (json.dumps(data, indent=2, sort_keys=True) + '\n').encode()

def module_bytes(module):
    # Gradle requires formatVersion to be the first JSON key.
    ordered = {'formatVersion': module['formatVersion']}
    ordered.update({k: v for k, v in module.items() if k != 'formatVersion'})
    return (json.dumps(ordered, indent=2) + '\n').encode()

def zip_bytes(entries):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, data in sorted(entries.items()):
            if name.startswith('/') or '..' in Path(name).parts:
                raise ValueError(f'Unsafe archive entry: {name}')
            item = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            item.compress_type = zipfile.ZIP_DEFLATED
            item.external_attr = 0o100644 << 16
            z.writestr(item, data)
    return out.getvalue()

def unzip(data):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = [i.filename for i in z.infolist() if not i.is_dir()]
        if len(names) != len(set(names)):
            raise ValueError('Duplicate ZIP entries')
        return {name: z.read(name) for name in names}

def package_root(name):
    name = '@expo/log-box' if name == 'expo-log-box' else name
    for base in [PRODUCER / 'node_modules', PRODUCER / 'node_modules/expo/node_modules']:
        p = base / name
        if (p / 'package.json').is_file():
            return p
    raise ValueError(f'Missing npm package {name}')

def npm_record(path, kind):
    metadata = json.loads((path / 'package.json').read_text())
    name = metadata['name']
    licenses = sorted(p for p in path.iterdir() if p.is_file() and
                      re.match(r'^(licen[sc]e|notice|copying)(\.|$)', p.name, re.I))
    # Expo packages and these split React Native packages inherit their monorepo licenses.
    if not licenses and (name.startswith('expo-') or name.startswith('@expo/')):
        licenses = [package_root('expo') / 'LICENSE']
    if not licenses and name.startswith('@react-native/'):
        licenses = [package_root('react-native') / 'LICENSE']
    if not licenses or not metadata.get('license'):
        raise ValueError(f'No evidenced license: {name}')
    repository = metadata.get('repository', {})
    origin = repository.get('url') if isinstance(repository, dict) else repository
    origin = origin or ('https://github.com/expo/expo' if name.startswith('@expo/') else None)
    if not origin:
        raise ValueError(f'No upstream origin for {name}')
    record = dict(component=name, version=metadata['version'], origin=origin,
                  license=metadata['license'], kind=kind, notices=[])
    files = {}
    for license_file in licenses:
        dest = f'licenses/npm/{name.replace("/", "_")}/{license_file.name}'
        files[dest] = license_file.read_bytes()
        record['notices'].append(dict(path=dest, source=str(license_file.relative_to(PRODUCER)), sha256=sha(files[dest])))
    return record, files

def native_headers():
    """Use Ninja's actual compiler dependency lists, not guessed linked-library licenses."""
    catalog = (package_root('react-native') / 'gradle/libs.versions.toml').read_text()
    components = json.loads((SDK / 'release/licenses/native-components.json').read_text())
    records, files = [], {}
    for component in components:
        key = component['component']
        version = re.search(r'^' + key + r'\s*=\s*"([^"]+)"', catalog, re.M).group(1)
        if version != component['version']:
            raise ValueError(f'Native license review required for {key} {version}')
        data = (SDK / 'release/licenses' / component['notice']).read_bytes()
        if sha(data) != component['sha256']:
            raise ValueError(f'Changed upstream license for {key}')
        dest = 'licenses/native/' + component['notice']
        files[dest] = data
        records.append(dict(component, kind='native header dependency', notices=[dict(path=dest, sha256=sha(data))]))
    # Pin to the producer's configured CMake. A clean checkout rebuilds these dependency databases.
    ninja = Path(os.environ['ANDROID_HOME']) / 'cmake/3.22.1/bin/ninja'
    directories = [PRODUCER / 'android/app', package_root('expo-modules-core') / 'android',
                   package_root('expo-updates') / 'android']
    headers = {}
    for directory in directories:
        builds = [p for p in (directory / '.cxx/RelWithDebInfo').glob('*/arm64-v8a/build.ninja')]
        if len(builds) != 1:
            raise ValueError(f'Expected one current native dependency database for {directory.name}')
        result = subprocess.run([str(ninja), '-C', str(builds[0].parent), '-t', 'deps'],
                                capture_output=True, text=True, check=True)
        if not result.stdout.strip():
            raise ValueError('Empty native compiler dependency evidence')
        for line in result.stdout.splitlines():
            name = line.strip()
            if '/prefab/modules/' not in name or not Path(name).is_file():
                continue
            relative = name.split('/prefab/modules/', 1)[1]
            content = Path(name).read_bytes()
            if relative in headers and headers[relative] != content:
                raise ValueError(f'Conflicting native header {relative}')
            headers[relative] = content
    families = set()
    preambles = {}
    for relative, data in sorted(headers.items()):
        body = relative.split('/include/', 1)[1]
        family = body.split('/')[0]
        if '/' in body:
            families.add(family)
        # Preserve initial upstream copyright/license comments verbatim, deduplicated by content.
        decoded = data.decode('utf-8', errors='strict')
        match = re.match(r'\s*(/\*.*?\*/|(?://[^\n]*\n)+)', decoded, re.S)
        if match:
            preamble = match.group(0)
            preambles.setdefault(preamble, []).append(relative)
    allowed = {'ReactCommon', 'boost', 'double-conversion', 'fbjni', 'fmt', 'folly', 'glog',
               'jsi', 'react', 'yoga', 'fast_float', 'cxxreact'}
    if families - allowed:
        raise ValueError(f'Unreviewed native header families: {families - allowed}')
    files['licenses/native/compiled-header-notices.txt'] = '\n\n'.join(
        '\n'.join(names) + '\n' + preamble for preamble, names in sorted(preambles.items())).encode()
    files['native-header-inputs.json'] = encode({name: sha(data) for name, data in sorted(headers.items())})
    return records, files


def inventory():
    source_map = PRODUCER / 'android/app/build/generated/sourcemaps/react/release/index.android.bundle.map'
    sources = json.loads(source_map.read_text())['sources']
    packages = set()
    for source in sources:
        if source.startswith('\0') or source == '/index.js':
            continue
        path = PRODUCER / source.lstrip('/')
        if not path.is_file():
            raise ValueError(f'Unresolved bundle source: {source}')
        for parent in path.parents:
            manifest = parent / 'package.json'
            if manifest.is_file() and json.loads(manifest.read_text()).get('name'):
                packages.add(parent)
                break
        else:
            raise ValueError(f'Unowned bundle source: {source}')
    records, files = [], {}
    for package in sorted(packages):
        record, notices = npm_record(package, 'bundled JavaScript')
        records.append(record)
        files.update(notices)
    for name in sorted(INTERNAL | set(MIRRORS.values())):
        record, notices = npm_record(package_root(name), 'rehosted Android')
        records.append(record)
        files.update(notices)
    native = [
        ('bspatch', 'expo-updates-57.0.23-vendored', 'BSD-2-Clause',
         package_root('expo-updates') / 'vendor/bspatch/LICENSE', 'https://github.com/expo/expo/tree/sdk-57/packages/expo-updates/vendor/bspatch'),
        ('bzip2', '1.0.8', 'bzip2-1.0.6',
         package_root('expo-updates') / 'android/src/main/cpp/third-party/bzip2/LICENSE', 'https://sourceware.org/bzip2/'),
        ('fbjni', '0.7.0', 'Apache-2.0', SDK / 'release/licenses/fbjni-0.7.0-LICENSE',
         'https://github.com/facebookincubator/fbjni/tree/v0.7.0'),
        ('NDK runtime notices', '27.1.12297006', 'See component licenses in NOTICE.toolchain',
         Path(os.environ['ANDROID_HOME']) / 'ndk/27.1.12297006/NOTICE.toolchain', 'https://android.googlesource.com/platform/ndk/'),
    ]
    for name, version, license_id, source, origin in native:
        dest = f'licenses/native/{name.replace(" ", "-")}.txt'
        files[dest] = source.read_bytes()
        records.append(dict(component=name, version=version, origin=origin, license=license_id,
                            kind='bundled native', notices=[dict(path=dest, sha256=sha(files[dest]))]))
    native_records, native_files = native_headers()
    records.extend(native_records)
    files.update(native_files)
    files['EXPO-MODIFICATIONS.md'] = (PRODUCER / 'patches/README.md').read_bytes()
    files['expo-updates.patch'] = (PRODUCER / 'patches/expo-updates+57.0.23.patch').read_bytes()
    files['NOTICE'] = (ROOT / 'NOTICE').read_bytes()
    files['DOOTAH-LICENSE'] = (ROOT / 'LICENSE').read_bytes()
    return records, files

def read_pom(path):
    node = ET.parse(path).getroot()
    for elem in node.iter():
        if not elem.tag.startswith('{'):
            elem.tag = f'{{{NS}}}' + elem.tag
    return node

def text(node, name):
    return node.findtext(f'{{{NS}}}{name}')

def child(node, name, value=None):
    elem = ET.SubElement(node, f'{{{NS}}}{name}')
    if value is not None:
        elem.text = value
    return elem

def set_text(node, name, value):
    elem = node.find(f'{{{NS}}}{name}')
    if elem is None:
        elem = child(node, name)
    elem.text = value

def coordinate(node):
    return tuple(text(node, n) for n in ('groupId', 'artifactId', 'version'))

def docs(artifact, sources, licenses):
    entries = dict(licenses)
    entries['index.html'] = (f'<!doctype html><meta charset="utf-8"><title>{html.escape(artifact)}</title>'
        f'<h1>{html.escape(artifact)}</h1><p>Release source reference. The sources below are the '
        'packaged source of this artifact, including upstream documentation comments. '
        'This is a source reference, not generated Javadoc.</p>'
        '<p>Runtime ABI 2; Logic ABI 1. Internal Expo modules are implementation details.</p>'
        '<p><a href="guide.html">SDK integration and publication guide</a></p><ul>' + ''.join(
        f'<li><a href="source/{html.escape(n)}.html">{html.escape(n)}</a></li>'
        for n in sorted(sources) if n.endswith(('.kt', '.java', '.groovy', '.cpp', '.h'))) + '</ul>').encode()
    entries['guide.html'] = ('<!doctype html><meta charset="utf-8"><pre>' +
        html.escape((SDK / 'release/README.md').read_text()) + '</pre>').encode()
    for name, data in sources.items():
        if name.endswith(('.kt', '.java', '.groovy', '.cpp', '.h')):
            entries['source/' + name + '.html'] = ('<!doctype html><meta charset="utf-8"><pre>' +
                html.escape(data.decode()) + '</pre>').encode()
    return zip_bytes(entries)

def audit_bytes(name, data, findings):
    patterns = [rb'/Users/[^/\s]+/', rb'/home/[^/\s]+/', rb'/private/(?:tmp|var)/',
                rb'/tmp/', rb'-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY',
                rb'AKIA[0-9A-Z]{16}', rb'gh[pousr]_[A-Za-z0-9]{30,}',
                rb'(?:sk_live_|xox[baprs]-)[A-Za-z0-9-]{16,}',
                rb'androiddebugkey', rb'Dootah Phase [0-9]',
                rb'34e81d97-661f-4f3b-ad2c-8d4e7a6546a6',
                rb'7d041833-4ea6-48b4-b581-357626bf5d5e',
                rb'MIIC4DCCAcigAwIBAgIIf5dtHBm6x7Ew']
    if any(re.search(p, data) for p in patterns):
        findings.append(name)
    # Public upstream trust anchor from locked expo-updates 57.0.23; never allow arbitrary PEMs.
    upstream_public_root = name.endswith('.aar!/assets/expo-root.pem') and sha(data) == '901e7b0da287ca154fc09d982d37769b09ee291255b239265b5b0a8b75165baa'
    if not upstream_public_root and re.search(r'(^|/)(ee|\.env)(/|$)|\.(pem|key|keystore|jks|apk)$', name):
        findings.append(name)
    if data.startswith(b'PK\x03\x04'):
        for entry, payload in unzip(data).items():
            audit_bytes(name + '!/' + entry, payload, findings)

def package(stage):
    output = stage / 'repository'
    if output.exists():
        raise ValueError('Refusing to overwrite release repository')
    records, notices = inventory()
    def pom_license_evidence(name, seen=None):
        seen = set() if seen is None else seen
        if name in seen:
            raise ValueError('POM parent cycle')
        seen.add(name)
        pom_file = stage / 'evidence' / name
        pom = read_pom(pom_file)
        licenses = [dict(name=text(x, 'name'), url=text(x, 'url'))
                    for x in pom.findall(f'{{{NS}}}licenses/{{{NS}}}license')]
        if licenses:
            return licenses, dict(pom=name, sha256=sha(pom_file.read_bytes()))
        parent = pom.find(f'{{{NS}}}parent')
        if parent is None and name == 'javax.inject_javax.inject_1.pom':
            source = SDK / 'release/licenses/javax.inject-1-NOTICE'
            return [dict(name='Apache License, Version 2.0', url='https://www.apache.org/licenses/LICENSE-2.0.txt')], dict(
                source='https://repo.maven.apache.org/maven2/javax/inject/javax.inject/1/javax.inject-1-sources.jar!/javax/inject/Inject.java',
                notice='licenses/javax.inject-1-NOTICE', sha256=sha(source.read_bytes()))
        if parent is None:
            raise ValueError(f'No license evidence for {name}')
        return pom_license_evidence('_'.join(coordinate(parent)) + '.pom', seen)
    # Keep transitive POM evidence distinct from code actually redistributed by Dootah.
    for graph in sorted((stage / 'evidence').glob('*-graph.json')):
        for entry in json.loads(graph.read_text()):
            pom = read_pom(stage / 'evidence' / entry['pom'])
            entry['licenses'], entry['licenseEvidence'] = pom_license_evidence(entry['pom'])
            scm = pom.find(f'{{{NS}}}scm')
            entry['origin'] = text(pom, 'url') or (text(scm, 'url') if scm is not None else None)
            entry['version'] = entry['component'].split(':')[-1]
            entry['origin'] = entry['origin'] or ('https://repo.maven.apache.org/maven2/' + entry['component'].split(':')[0].replace('.', '/') + '/' + '/'.join(entry['component'].split(':')[1:]))
            records.append(entry)
    notices['licenses/javax.inject-1-NOTICE'] = (SDK / 'release/licenses/javax.inject-1-NOTICE').read_bytes()
    notices['inventory.json'] = encode(records)
    bundle = {'META-INF/dootah/' + n: d for n, d in notices.items()}
    publications = []
    for path in sorted((stage / 'raw').rglob('*.pom')):
        pom = read_pom(path)
        group, artifact, version = coordinate(pom)
        if artifact not in OWNED | INTERNAL | MIRRORS.keys():
            raise ValueError(f'Unexpected publication {group}:{artifact}')
        is_owned = artifact in OWNED
        base = f'{artifact}-{version}'
        binaries = {}
        for file in path.parent.iterdir():
            suffix = file.name.removeprefix(base)
            if suffix in ('.aar', '.jar', '-sources.jar'):
                content = unzip(file.read_bytes())
                if suffix == '-sources.jar' and artifact == 'runtime-v2':
                    bootstrap = package_root('react-native') / 'ReactAndroid/cmake-utils/default-app-setup/OnLoad.cpp'
                    content['native/bootstrap/OnLoad.cpp'] = bootstrap.read_bytes()
                    for name in ['autolinking.cpp', 'autolinking.h']:
                        content['native/bootstrap/' + name] = (PRODUCER / 'android/app/build/generated/autolinking/src/main/jni' / name).read_bytes()
                if suffix == '-sources.jar' and not is_owned:
                    upstream = package_root(MIRRORS.get(artifact, artifact))
                    # AGP's sources JAR omits native sources. Include the maintained native inputs.
                    for directory in ['android/src/main/cpp', 'common/cpp', 'vendor']:
                        for source in sorted((upstream / directory).rglob('*')):
                            if source.is_file():
                                content['native/' + str(source.relative_to(upstream))] = source.read_bytes()
                content.update(bundle)
                if is_owned:
                    content['META-INF/LICENSE'] = (ROOT / 'LICENSE').read_bytes()
                else:
                    content['META-INF/LICENSE'] = (package_root('expo') / 'LICENSE').read_bytes()
                if suffix == '.aar':
                    content.update({'assets/dootah-licenses/' + n: d for n, d in notices.items()})
                # Normalize nested ZIP timestamps too; never change compiled class/native bytes.
                for n in list(content):
                    if n.endswith('.jar'):
                        content[n] = zip_bytes(unzip(content[n]))
                binaries[suffix] = zip_bytes(content)
        if artifact != 'dev.dootah.gradle.plugin':
            if '-sources.jar' not in binaries or not any(x in binaries for x in ('.jar', '.aar')):
                raise ValueError(f'Missing binary/sources for {artifact}')
            binaries['-javadoc.jar'] = docs(artifact, unzip(binaries['-sources.jar']), bundle)
        module_path = path.with_suffix('.module')
        module = json.loads(module_path.read_text()) if module_path.is_file() else None
        publications.append(dict(old=(group, artifact, version), pom=pom, module=module,
                                 owned=is_owned, binaries=binaries))
    expected = OWNED | INTERNAL | MIRRORS.keys()
    if {p['old'][1] for p in publications} != expected or len(publications) != len(expected):
        raise ValueError('Release set does not match the artifact allowlist')
    props = dict(line.split('=', 1) for line in (SDK / 'version.properties').read_text().splitlines()
                 if line and not line.startswith('#'))
    # Hash all payloads plus unmodified dependency metadata. A change in any internal payload or
    # dependency gets a new coordinate, including changed native bytes with unchanged source.
    digest = hashlib.sha256()
    digest.update(Path(__file__).read_bytes())
    digest.update(encode({key: os.environ.get(key) for key in ['DOOTAH_DEVELOPER_ID', 'DOOTAH_DEVELOPER_NAME']}))
    for p in publications:
        if not p['owned']:
            digest.update(encode(p['old']))
            digest.update(ET.tostring(p['pom']))
            digest.update(encode(p['module']))
            for suffix, data in sorted(p['binaries'].items()):
                digest.update(suffix.encode()); digest.update(data)
    internal_version = props['dootah.internal.version'] + '.sha256-' + digest.hexdigest()
    resolved_versions = {tuple(r['component'].split(':')[:2]): r['version'] for r in records if r['kind'] == 'transitive'}
    mapping = {p['old']: (p['old'] if p['owned'] else ('dev.dootah.internal', p['old'][1], internal_version))
               for p in publications}
    for p in publications:
        group, artifact, version = mapping[p['old']]
        pom = p['pom']
        for n, v in zip(('groupId', 'artifactId', 'version'), (group, artifact, version)):
            set_text(pom, n, v)
        for dep in pom.findall(f'.//{{{NS}}}dependency'):
            old = coordinate(dep)
            if old in mapping:
                for n, v in zip(('groupId', 'artifactId', 'version'), mapping[old]):
                    set_text(dep, n, v)
            elif old[2] is None and old[:2] in resolved_versions:
                set_text(dep, 'version', resolved_versions[old[:2]])
            elif old[2] is None:
                raise ValueError(f'Unversioned dependency {old}')
        for n in ['name', 'description', 'url', 'scm', 'licenses', 'developers', 'organization']:
            for elem in pom.findall(f'{{{NS}}}{n}'):
                pom.remove(elem)
        set_text(pom, 'name', ('Dootah ' if p['owned'] else 'Dootah redistribution of ') + artifact)
        set_text(pom, 'description', 'Bounded Kotlin/Compose OTA ' + ('SDK' if p['owned'] else 'implementation dependency; upstream Expo code, see bundled notices'))
        set_text(pom, 'url', PROJECT_URL)
        scm = child(pom, 'scm')
        child(scm, 'url', PROJECT_URL)
        child(scm, 'connection', 'scm:git:' + PROJECT_URL + '.git')
        license_node = child(child(pom, 'licenses'), 'license')
        child(license_node, 'name', 'Apache License, Version 2.0' if p['owned'] else 'MIT License')
        child(license_node, 'url', 'https://www.apache.org/licenses/LICENSE-2.0.txt' if p['owned'] else 'https://github.com/expo/expo/blob/sdk-57/LICENSE')
        child(license_node, 'distribution', 'repo')
        # Optional maintainer-supplied metadata. Public export fails closed without it.
        if os.environ.get('DOOTAH_DEVELOPER_ID') and os.environ.get('DOOTAH_DEVELOPER_NAME'):
            dev = child(child(pom, 'developers'), 'developer')
            child(dev, 'id', os.environ['DOOTAH_DEVELOPER_ID'])
            child(dev, 'name', os.environ['DOOTAH_DEVELOPER_NAME'])
        dest = output / group.replace('.', '/') / artifact / version
        dest.mkdir(parents=True)
        base = f'{artifact}-{version}'
        for suffix, data in p['binaries'].items():
            (dest / (base + suffix)).write_bytes(data)
        ET.indent(pom)
        pom_data = ET.tostring(pom, encoding='utf-8', xml_declaration=True)
        if p['module']:
            pom_data = pom_data.replace(b'<modelVersion>', b'<!-- do_not_remove: published-with-gradle-metadata -->\n  <modelVersion>', 1)
        (dest / (base + '.pom')).write_bytes(pom_data)
        if p['module']:
            module = p['module']
            module['component'].update(group=group, module=artifact, version=version)
            for variant in module['variants']:
                for dep in variant.get('dependencies', []):
                    key = (dep['group'], dep['module'], dep.get('version', {}).get('requires'))
                    if key in mapping:
                        dg, da, dv = mapping[key]
                        dep.update(group=dg, module=da, version={'requires': dv})
                for f in variant.get('files', []):
                    suffix = f['name'].removeprefix(f"{p['old'][1]}-{p['old'][2]}")
                    data = p['binaries'][suffix]
                    f.update(name=base + suffix, url=base + suffix, size=len(data))
                    for algorithm in ['sha512', 'sha256', 'sha1', 'md5']:
                        f[algorithm] = hashlib.new(algorithm, data).hexdigest()
            (dest / (base + '.module')).write_bytes(module_bytes(module))
    (stage / 'inventory.json').write_bytes(encode(records))
    findings = []
    class_inventory = {}
    for path in sorted(output.rglob('*')):
        if path.is_file():
            audit_bytes(str(path.relative_to(output)), path.read_bytes(), findings)
            if path.suffix in ('.aar', '.jar') and not path.name.endswith(('-sources.jar', '-javadoc.jar')):
                entries = unzip(path.read_bytes())
                if path.suffix == '.aar':
                    entries = unzip(entries['classes.jar'])
                classes = sorted(n for n in entries if n.endswith('.class'))
                artifact = path.parent.parent.name
                prefixes = ('dev/dootah/runtime/', 'dev/dootah/portable/') if artifact == 'runtime-v2' else (
                    ('dev/dootah/gradle/', 'dev/dootah/identity/') if artifact == 'dootah-android-plugin' else
                    ('expo/', 'com/facebook/react/') if artifact == 'expo-modules-core' else ('expo/', 'inline/modules/') if artifact == 'expo' else ('expo/',))
                if not classes or any(not name.startswith(prefixes) or re.search(r'(^|/)[^/]*Test(?:\$|\.)', name) for name in classes):
                    raise ValueError(f'Unexpected classes in {artifact}')
                class_inventory[artifact] = classes
    (stage / 'classes.json').write_bytes(encode(class_inventory))
    (stage / 'audit.json').write_bytes(encode(dict(findings=sorted(set(findings)), internalVersion=internal_version)))
    if findings:
        raise ValueError(f'Artifact content audit failed: {len(set(findings))} entries; see audit.json')
    checksums(output, stage / 'SHA256SUMS')
    print(f'Staged {len(publications)} publications; internal version {internal_version}')

def checksums(repository, manifest):
    lines = []
    for path in sorted(repository.rglob('*')):
        if path.is_file() and path.suffix not in ('.sha256', '.sha512', '.sha1', '.md5'):
            data = path.read_bytes()
            lines.append(f'{sha(data)}  {path.relative_to(repository).as_posix()}\n')
            for algorithm in ['sha256', 'sha512', 'sha1', 'md5']:
                path.with_name(path.name + '.' + algorithm).write_text(hashlib.new(algorithm, data).hexdigest())
    manifest.write_text(''.join(lines))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', type=Path)
    args = parser.parse_args()
    package(args.stage.resolve())
