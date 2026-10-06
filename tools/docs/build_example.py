#!/usr/bin/env python3
"""Build the quickstart's exact snippets against a reviewed local public Maven stage.

Uses the normal Gradle cache unless GRADLE_USER_HOME is set. No server/device/publication.
Temporary certificate/private key are for this build only and deleted afterward.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', type=Path, required=True)
    args = parser.parse_args()
    stage = args.stage.resolve()
    subprocess.run(['python3', str(ROOT / 'v2/android-sdk/release/validate.py'), str(stage)], check=True)
    doc = (ROOT / 'docs/getting-started/quickstart.md').read_text()
    blocks = lambda language: re.findall(r'```' + language + r'\n(.*?)```', doc, re.S)
    settings, app = blocks('groovy')
    application, activity = blocks('kotlin')
    manifest, = blocks('xml')
    with tempfile.TemporaryDirectory(prefix='dootah-doc-example-') as directory:
        work = Path(directory)
        work.chmod(0o700)
        project = work / 'app-project'
        project.mkdir()
        for name in ['gradlew', 'gradlew.bat', 'gradle']:
            source = ROOT / 'v2/native-consumer' / name
            if source.is_dir():
                shutil.copytree(source, project / name)
            else:
                shutil.copy2(source, project / name)
        (project / 'settings.gradle').write_text(settings)
        (project / 'gradle.properties').write_text('android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g\norg.gradle.workers.max=2\n')
        source = project / 'app/src/main/java/example/dootah'
        source.mkdir(parents=True)
        (project / 'app/build.gradle').write_text(app)
        (source / 'MyApplication.kt').write_text(application)
        (source / 'MainActivity.kt').write_text(activity)
        (project / 'app/src/main/AndroidManifest.xml').write_text(manifest)
        example = ROOT / 'v2/examples/basic-text'
        shutil.copy2(example / 'baseline/CheckoutScreen.kt', source / 'CheckoutScreen.kt')
        env = dict(os.environ, DOOTAH_MAVEN_REPOSITORY=str(stage / 'repository'),
                   DOOTAH_APP_ID='00000000-0000-4000-8000-000000000001',
                   DOOTAH_CLOUD_URL='https://updates.example.invalid')
        def run(command, label):
            result = subprocess.run(list(map(str, command)), cwd=project, env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            if result.returncode:
                # Only this isolated example runs; no customer passwords/tokens are inputs.
                raise RuntimeError(label + ' failed:\n' + result.stdout[-8000:])
            return result.stdout
        run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
             '-subj', '/CN=Documentation build test/', '-keyout', work / 'test.key',
             '-out', project / 'app/update-certificate.pem'], 'temporary certificate')
        (work / 'test.key').unlink()
        run([project / 'gradlew', '--no-daemon', '--console=plain',
             ':app:dootahRetainDebugRelease', ':app:assembleRelease'], 'documented Gradle build')
        record = project / 'app/build/outputs/dootah/debug/retained/release.json'
        reports = {
            'debug': json.loads((record.parent / 'hooks.json').read_text()),
            'release': json.loads((project / 'app/build/outputs/dootah/release/hooks.json').read_text())
        }
        retained = json.loads(record.read_text())
        assert retained['metadata']['sdkVersion'] == '0.1.0-alpha.1', retained['metadata']
        assert reports['release']['sdkVersion'] == '0.1.0-alpha.1'
        for report in reports.values():
            assert report['hooks'] == 1, report
        private = work / 'publisher'
        private.mkdir(mode=0o700)
        cli = ROOT / 'v2/publishing/build/install/dootah-publishing/bin/dootah'
        run([cli, 'release', 'import', '--record', record, '--source-root', project / 'app',
             '--config', private / 'publish.json'], 'documented release import')
        shutil.copy2(example / 'ota/CheckoutScreen.kt', source / 'CheckoutScreen.kt')
        analysis = run([cli, 'analyze', private / 'publish.json'], 'documented OTA analysis')
        assert '1 changed OTA-capable' in analysis and 'CheckoutScreen' in analysis, analysis
        print(json.dumps(dict(passed=True, debugRetention=True, releaseBuild=True,
                              hooks={v:r['hooks'] for v,r in reports.items()},
                              releaseImport=True, supportedEditAnalysis=True,
                              stageManifestSha256=hashlib.sha256((stage/'SHA256SUMS').read_bytes()).hexdigest(),
                              gradleCacheIsolationClaimed=False,
                              deviceOta=False, externalPublication=False), indent=2))


if __name__ == '__main__':
    main()
