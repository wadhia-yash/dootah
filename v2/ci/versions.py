"""Bind the frozen source contract, staged coordinates and candidate metadata."""
import hashlib
import io
import json
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from gates import MANIFEST, require


def source(root):
    expected = json.loads(MANIFEST.read_text())['frozen']
    props = dict(line.split('=', 1) for line in (root/'v2/android-sdk/version.properties').read_text().splitlines()
                 if line and not line.startswith('#'))
    require(props == {'dootah.public.version': expected['publicVersion'],
                      'dootah.sdk.version': expected['developmentVersion'],
                      'dootah.internal.version': expected['internalPrefix']}, 'Frozen version source mismatch')
    portable = (root/'v2/portable/src/main/java/dev/dootah/portable/PortableProgram.java').read_text()
    require(re.search(r'ABI = 2, LOGIC_ABI = 1;', portable), 'Frozen ABI mismatch')
    for name in ['v2/runtime-spike/android/gradle/wrapper/gradle-wrapper.properties',
                 'v2/native-consumer/gradle/wrapper/gradle-wrapper.properties']:
        require('distributionSha256Sum=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06' in (root/name).read_text(), 'Unpinned Gradle wrapper')
    pins = json.loads((root/'v2/ci/pins.json').read_text())
    for name, digest in pins.items():
        require(hashlib.sha256((root/name).read_bytes()).hexdigest() == digest, 'Reviewed dependency pin changed: ' + name)
    return expected


def stage_versions(root, stage):
    expected = source(root)
    sys.path.insert(0, str(root/'v2/android-sdk/release'))
    from validate import validate
    from package import NS, OWNED, coordinate, read_pom
    validate(stage)
    internal = json.loads((stage/'audit.json').read_text())['internalVersion']
    require(re.fullmatch(re.escape(expected['internalPrefix']) + r'\.sha256-[0-9a-f]{64}', internal), 'Nonimmutable internal version')
    for path in (stage/'repository').rglob('*.pom'):
        group, name, version = coordinate(read_pom(path))
        require(version == (expected['publicVersion'] if name in OWNED else internal), 'Stale POM version')
        if name == 'dev.dootah.gradle.plugin':
            deps = ET.parse(path).findall(f'.//{{{NS}}}dependency')
            require([coordinate(d) for d in deps] == [('dev.dootah', 'dootah-android-plugin', expected['publicVersion'])], 'Stale marker')
        if name == 'dootah-android-plugin':
            with zipfile.ZipFile(path.with_suffix('.jar')) as archive:
                require(archive.read('dev/dootah/gradle/sdk-version.txt').decode().strip() == expected['publicVersion'], 'Stale plugin runtime version')
        if name == 'runtime-v2':
            with zipfile.ZipFile(path.with_suffix('.aar')) as aar:
                with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as classes:
                    require(expected['publicVersion'].encode() in classes.read('dev/dootah/runtime/BuildConfig.class'), 'Stale runtime binary version')
    receipt = json.loads((stage/'consumer-result.json').read_text())
    require(receipt['publicVersion'] == expected['publicVersion'], 'Stale consumer version')
    for variant in ['debug', 'release']:
        report = receipt['hooks'][variant]
        require(report['sdkVersion'] == expected['publicVersion'] and report['hooks'] >= {'debug':13,'release':11}[variant], 'Missing consumer hooks')
    return dict(expected, internalVersion=internal,
                stageManifestSha256=hashlib.sha256((stage/'SHA256SUMS').read_bytes()).hexdigest())
