#!/usr/bin/env python3
"""Build an isolated consumer; Dootah can resolve only from the supplied stage."""
import json
import hashlib
import re
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from validate import validate


def configure_distribution(properties, archive):
    """Optional upstream-tool ZIP; never copies a Gradle dependency cache."""
    if not archive:
        return 'network'
    content = properties.read_text()
    expected = re.search(r'^distributionSha256Sum=([0-9a-f]{64})$', content, re.M)
    if expected is None or hashlib.sha256(Path(archive).read_bytes()).hexdigest() != expected.group(1):
        raise ValueError('Upstream Gradle ZIP does not match the fixture wrapper checksum')
    content = re.sub(r'^distributionUrl=.*$', 'distributionUrl=' + Path(archive).resolve().as_uri(), content, flags=re.M)
    properties.write_text(content)
    return 'checksum-verified upstream ZIP'


def main():
    sdk = Path(__file__).resolve().parents[1]
    stage = Path(sys.argv[1]).resolve()
    repo = stage / 'repository'
    if not (stage / 'SHA256SUMS').is_file():
        raise SystemExit('Stage has not passed packaging/audit')
    props = dict(line.split('=', 1) for line in (sdk / 'version.properties').read_text().splitlines()
                 if line and not line.startswith('#'))
    version = props['dootah.public.version']
    validate(stage)
    work = Path(tempfile.mkdtemp(prefix='dootah-release-consumer-'))
    consumer = work / 'consumer'
    consumer.mkdir()
    source = sdk.parent / 'native-consumer'
    for name in ['gradlew', 'gradlew.bat', 'gradle', 'gradle.properties', 'build.gradle']:
        src = source / name
        if src.is_dir():
            shutil.copytree(src, consumer / name)
        else:
            shutil.copy2(src, consumer / name)
    distribution_source = configure_distribution(consumer / 'gradle/wrapper/gradle-wrapper.properties',
                                                  os.environ.get('DOOTAH_GRADLE_DISTRIBUTION_ZIP'))
    shutil.copytree(source / 'app/src', consumer / 'app/src')
    shutil.copy2(source / 'app/build.gradle', consumer / 'app/build.gradle')
    # Fresh throwaway certificate; no repository-local trust material or private key is copied.
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                    '-subj', '/CN=Local packaging test/', '-keyout', str(work / 'test.key'),
                    '-out', str(consumer / 'app/update-certificate.pem')], check=True, capture_output=True)
    (work / 'test.key').unlink()
    repository_block = '''
        repositories {
            exclusiveContent {
                forRepository { maven { url = uri(%s) } }
                filter { includeGroupByRegex('dev[.]dootah([.].*)?') }
            }
            google(); mavenCentral(); gradlePluginPortal()
        }
    ''' % json.dumps(repo.as_uri())
    (consumer / 'settings.gradle').write_text('pluginManagement {\n' +
        "    plugins { id 'dev.dootah' version '" + version + "' }\n" + repository_block +
        '}\ndependencyResolutionManagement {\n' + repository_block +
        "}\nrootProject.name = 'ReleaseConsumer'\ninclude ':app'\n")
    # Keep the fixture's meaningful instrumentation cases, but use nonproduction app configuration.
    app = consumer / 'app/build.gradle'
    app.write_text(app.read_text().replace('7d041833-4ea6-48b4-b581-357626bf5d5e',
                                           '00000000-0000-4000-8000-000000000001'))
    env = dict(os.environ, GRADLE_USER_HOME=str(work / 'gradle-home'))
    # Refuse untrusted global Gradle init scripts by starting from an empty Gradle user home.
    print(f'Isolated consumer: {work}', flush=True)
    with (stage / 'consumer.log').open('w') as log:
        subprocess.run([str(consumer / 'gradlew'), '-p', str(consumer), '--no-daemon',
                        '--no-build-cache', '--console=plain', ':app:assembleDebug', ':app:assembleRelease'],
                       env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    reports = {}
    for variant in ['debug', 'release']:
        report = json.loads((consumer / f'app/build/outputs/dootah/{variant}/hooks.json').read_text())
        if report['hooks'] <= 0 or report['sdkVersion'] != version:
            raise RuntimeError(f'Invalid hook report: {variant}')
        reports[variant] = report
    # Only the public version may occur in the fresh cache. Internal artifacts have content versions.
    cache = work / 'gradle-home/caches/modules-2/files-2.1'
    for group in cache.glob('dev.dootah*'):
        for module in group.iterdir():
            for resolved in module.iterdir():
                if group.name == 'dev.dootah' and resolved.name != version:
                    raise RuntimeError(f'Unexpected cached version: {resolved}')
    (stage / 'consumer-result.json').write_text(json.dumps(dict(
        publicVersion=version, freshGradleHome=True, exclusiveDootahRepository=True,
        debugD8=True, releaseR8=True, hooks=reports, gradleDistributionSource=distribution_source,
        stageManifestSha256=hashlib.sha256((stage / 'SHA256SUMS').read_bytes()).hexdigest()), indent=2) + '\n')
    print('Fresh consumer Debug/D8 and Release/R8 passed; hook reports verified.', flush=True)


if __name__ == '__main__':
    main()
