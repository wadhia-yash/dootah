#!/usr/bin/env python3
"""Phase 9C local acceptance. No external deployment, customer DB edits or ad-hoc helpers.

Requires Docker, JDK 21, Android SDK, staged 9B repository and the audited xprem binary.
Creates disposable PostgreSQL/xprem state, boots the existing Cloud process, drives the
installed CLI, builds a fresh Android workspace, then runs every Cloud integration test.
Only operator prerequisites (database/logins, migration, initial users, delivery bindings)
use operator access. All organizations/apps/environments/tokens/releases use public APIs.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / 'v2/publishing'


def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', type=Path, required=True)
    parser.add_argument('--xprem-binary', type=Path, required=True)
    args = parser.parse_args()
    stage = args.stage.resolve()
    assert (stage / 'SHA256SUMS').is_file() and args.xprem_binary.is_file()
    assert os.environ.get('JAVA_HOME') and os.environ.get('ANDROID_HOME')
    work = Path(tempfile.mkdtemp(prefix='dootah-9c-'))
    work.chmod(0o700)
    print(f'Private acceptance workspace: {work}', flush=True)
    log = (work / 'acceptance.log').open('w')
    def run(argv, *, env=None, data=None, output=False, expected=0):
        result = subprocess.run(list(map(str, argv)), input=data, text=True,
                                stdout=subprocess.PIPE if output else log, stderr=subprocess.PIPE if output else subprocess.STDOUT,
                                env=dict(os.environ, **(env or {})), cwd=work)
        if output: log.write(result.stdout + result.stderr)
        log.flush()
        if result.returncode != expected:
            raise RuntimeError(f'{Path(str(argv[0])).name} failed ({result.returncode}); inspect private acceptance.log')
        return result.stdout if output else None

    def save(path, value):
        path.write_text(json.dumps(value) if not isinstance(value, str) else value)
        path.chmod(0o600)

    network = 'dootah-9c-' + secrets.token_hex(4)
    pgname, xpname = network + '-pg', network + '-xp'
    dbport, xpport, cloudport = port(), port(), port()
    base = f'http://127.0.0.1:{cloudport}'
    dbpassword, password = secrets.token_hex(24), 'Aa1!' + secrets.token_hex(24)
    password_file = work / 'password'; save(password_file, password)
    postgres_env = work / 'postgres.env'; save(postgres_env, f'POSTGRES_PASSWORD={dbpassword}\n')
    bindings_file = work / 'bindings.json'; save(bindings_file, {})
    process = None
    try:
        run(['docker', 'network', 'create', network])
        run(['docker', 'run', '-d', '--name', pgname, '--network', network, '--network-alias', 'postgres',
             '-p', f'127.0.0.1:{dbport}:5432', '--env-file', postgres_env, 'postgres:17-alpine'])
        for _ in range(60):
            ready = subprocess.run(['docker', 'exec', pgname, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres'], capture_output=True)
            if ready.returncode == 0: break
            time.sleep(0.5)
        else: raise RuntimeError('PostgreSQL unavailable')
        # Operator setup only. No customer/application state is inserted with SQL.
        run(['docker', 'exec', '-i', pgname, 'psql', '-U', 'postgres', '-v', 'ON_ERROR_STOP=1'], data=f'''
CREATE ROLE dootah_cloud_runtime NOLOGIN;
CREATE ROLE migration LOGIN PASSWORD '{dbpassword}';
CREATE ROLE runtime LOGIN PASSWORD '{dbpassword}' IN ROLE dootah_cloud_runtime;
CREATE DATABASE cloud OWNER migration;
CREATE DATABASE xprem;
''')
        migration = {'DOOTAH_CLOUD_DATABASE_URL': f'postgresql://migration:{dbpassword}@127.0.0.1:{dbport}/cloud'}
        run(['node', ROOT / 'v2/cloud/admin.mjs', 'migrate'], env=migration)
        for email in ['owner-a@local.test', 'owner-b@local.test', 'viewer@local.test']:
            run(['node', ROOT / 'v2/cloud/admin.mjs', 'user', email], env=dict(migration, DOOTAH_INITIAL_PASSWORD=password))
        run([TOOL / 'gradlew', '-p', TOOL, 'test', 'installDist', '--console=plain'])
        # Copy only the supported installable tool; no repo-relative JVM/fixture dependency survives.
        shutil.copytree(TOOL / 'build/install/dootah-publishing', work / 'tool')
        cli = work / 'tool/bin/dootah'
        run([cli, '--help'])
        run([ROOT / 'v2/cloud/validator.sh'])
        cloud_env = dict(os.environ, DOOTAH_CLOUD_DATABASE_URL=f'postgresql://runtime:{dbpassword}@127.0.0.1:{dbport}/cloud',
                         DOOTAH_CLOUD_ORIGIN=base, DOOTAH_ALLOW_LOCAL_HTTP='true', PORT=str(cloudport),
                         DOOTAH_DELIVERY_BINDINGS=str(bindings_file), DOOTAH_JAVA=str(Path(os.environ['JAVA_HOME']) / 'bin/java'),
                         DOOTAH_VALIDATOR_CLASSPATH=f'{ROOT}/v2/cloud/build/validator:{work}/tool/lib/*')
        process = subprocess.Popen(['node', str(ROOT / 'v2/cloud/server.mjs')], env=cloud_env,
                                   stdout=log, stderr=log)
        for _ in range(60):
            try:
                with urllib.request.urlopen(base + '/ready', timeout=1) as response:
                    if response.status == 200: break
            except OSError: time.sleep(0.5)
        else: raise RuntimeError('Cloud unavailable')

        def command(argv, session=None, org=None, token=None, json_output=True, expected=0):
            flags = ['--url', base, '--allow-local-http']
            if session: flags += ['--session-file', session]
            if org: flags += ['--org', org]
            if token: flags += ['--token-file', token]
            out = run([cli, *argv, *flags], output=True, expected=expected)
            return json.loads(out) if json_output and expected == 0 else out

        # Create all customer state through the installed CLI, not harness HTTP/SQL shortcuts.
        customers = {}
        for label in ['a', 'b']:
            session = work / f'{label}.session'
            command(['login', '--email', f'owner-{label}@local.test', '--password-file', password_file], session=session)
            org = command(['org', 'create', '--name', f'Acceptance {label}'], session=session)['id']
            assert any(x['id'] == org for x in command(['org', 'list'], session=session)['items'])
            app = command(['app', 'create', '--name', 'Fresh customer'], session=session, org=org)['id']
            assert any(x['id'] == app for x in command(['app', 'list'], session=session, org=org)['items'])
            customers[label] = dict(session=session, org=org, app=app)

        # Existing supported xprem provision command; isolated signing/storage, no production state.
        assets = work / 'assets'; assets.mkdir()
        xp_env = work / 'xprem.env'
        save(xp_env, '\n'.join([f'DB_URL=postgresql://postgres:{dbpassword}@postgres:5432/xprem?sslmode=disable',
             'STORAGE_MODE=local', 'LOCAL_BUCKET_BASE_PATH=/state/assets', f'BASE_URL={base}',
             'BIND_TO_ADDRESS=0.0.0.0', 'PORT=3100', 'DB_KEYS_MASTER_KEY_B64=' + __import__('base64').b64encode(secrets.token_bytes(32)).decode(),
             'JWT_SECRET=' + secrets.token_hex(32), 'ADMIN_EMAIL=operator@local.test', 'ADMIN_PASSWORD=' + password,
             'DISABLE_TELEMETRY=true', 'DISABLE_DEVICE_TELEMETRY=true', 'BUNDLE_DIFFING=false', 'SKIP_LEGACY_APP_ID_FALLBACK=true', '']))
        docker = ['docker', 'run', '--network', network, '--env-file', xp_env,
                  '-v', f'{args.xprem_binary.resolve()}:/dootah-server:ro', '-v', f'{work}:/state',
                  'golang@sha256:8ac98ca534ac3f51e1f420a1dd2c15e74c75cfa0f23f3ad27eb5d7236c349a0c', '/dootah-server']
        bindings = {}
        for c in customers.values():
            raw = run(docker[:2] + ['--rm'] + docker[2:] + ['-provision'], output=True)
            provision = json.loads(raw[raw.index('{'):])
            bindings[c['app']] = dict(provision, base=f'http://127.0.0.1:{xpport}', publicOrigin=base)
            c['certificate'] = provision['certificate']
        save(bindings_file, bindings)
        run(docker[:2] + ['-d', '--name', xpname, '-p', f'127.0.0.1:{xpport}:3100'] + docker[2:])

        # A new, tiny Android application, independent of native-consumer and its fixtures.
        consumer = work / 'consumer'; consumer.mkdir()
        shutil.copy(TOOL / 'gradlew', consumer / 'gradlew')
        shutil.copytree(TOOL / 'gradle', consumer / 'gradle')
        (consumer / 'gradle.properties').write_text('org.gradle.jvmargs=-Xmx3g\nandroid.useAndroidX=true\norg.gradle.workers.max=4\n')
        repo = (stage / 'repository').as_uri()
        (consumer / 'settings.gradle').write_text(f'''pluginManagement {{
    includeBuild({json.dumps(str(ROOT / 'v2/android-sdk/gradle-plugin'))})
    repositories {{ google(); mavenCentral(); gradlePluginPortal() }}
}}
dependencyResolutionManagement {{ repositories {{
    exclusiveContent {{ forRepository {{ maven {{ url = uri('{repo}') }} }}; filter {{ includeGroupByRegex('dev[.]dootah([.].*)?') }} }}
    google(); mavenCentral()
}} }}
rootProject.name='FreshCustomer'
include ':app'
''')
        appdir = consumer / 'app'; source = appdir / 'src/main/kotlin/example/Screen.kt'
        source.parent.mkdir(parents=True)
        baseline = '''package example
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.BasicText
class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); setContent { Screen("Native") } }
}
@Composable fun Screen(label: String) { BasicText("$label baseline") }
'''
        source.write_text(baseline)
        (appdir / 'src/main/AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<application android:name="dev.dootah.runtime.DootahApplication" android:label="Fresh Customer" android:allowBackup="false">
<activity android:name=".MainActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>
</application></manifest>''')
        debug = appdir / 'src/debug'; (debug / 'res/xml').mkdir(parents=True)
        (debug / 'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:networkSecurityConfig="@xml/dootah_local_network"/></manifest>')
        (debug / 'res/xml/dootah_local_network.xml').write_text('<network-security-config><base-config cleartextTrafficPermitted="false"/><domain-config cleartextTrafficPermitted="true"><domain includeSubdomains="false">127.0.0.1</domain></domain-config></network-security-config>')
        def configure(c):
            (appdir / 'update-certificate.pem').write_text(c['certificate'])
            (appdir / 'build.gradle').write_text(f'''plugins {{
 id 'com.android.application' version '8.12.0'
 id 'org.jetbrains.kotlin.android' version '2.1.20'
 id 'org.jetbrains.kotlin.plugin.compose' version '2.1.20'
 id 'dev.dootah'
}}
android {{ namespace 'example'; compileSdk 36
 defaultConfig {{ applicationId 'example.customer'; minSdk 29; targetSdk 36; versionCode 1; versionName '1' }}
 compileOptions {{ sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }}
 kotlinOptions {{ jvmTarget = '17' }}
 buildFeatures {{ compose true }}
 buildTypes {{ release {{ minifyEnabled true; signingConfig signingConfigs.debug; proguardFiles getDefaultProguardFile('proguard-android-optimize.txt') }} }}
}}
dootah {{ appId='{c['app']}'; updateUrl='https://updates.example.test/manifest'; debugUpdateUrl='{base}/manifest'
 channel='development'; runtimeVersion='dootah-v2-logic-2'; publicCertificate=file('update-certificate.pem')
 allowLocalHttp=true; requireHooks=true
}}
dependencies {{ implementation 'androidx.activity:activity-compose:1.9.3'; implementation 'androidx.compose.foundation:foundation:1.7.6' }}
''')
        build = [consumer / 'gradlew', '-p', consumer, '-PdootahReleaseStage', '--console=plain', '--configuration-cache',
                 ':app:dootahRetainDebugRelease']
        tenants = {}
        for label, c in customers.items():
            configure(c); source.write_text(baseline)
            run(build)
            retained = appdir / 'build/outputs/dootah/debug/retained'
            before = (retained / 'release.json').read_bytes()
            run(build)
            assert (retained / 'release.json').read_bytes() == before
            record = work / f'{label}-record'; shutil.copytree(retained, record)
            destination = work / label; destination.mkdir()
            config = destination / 'publish.json'
            command(['release', 'import', '--record', record / 'release.json', '--source-root', appdir, '--config', config])
            env = command(['env', 'create', '--config', config], session=c['session'], org=c['org'])['id']
            duplicate = command(['contract', 'register', '--config', config], session=c['session'], org=c['org'])
            assert duplicate['id'] == env and duplicate['alreadyRegistered']
            command(['contract', 'register', '--config', config, '--runtime', 'mismatch'], session=c['session'], org=c['org'], expected=1)
            command(['contract', 'register', '--config', config, '--app', customers['b' if label == 'a' else 'a']['app']], session=c['session'], org=c['org'], expected=1)
            token = destination / 'token'
            command(['token', 'create', '--scopes', 'app:read,release:read,release:publish,release:control,telemetry:read',
                     '--days', '1', '--app', c['app'], '--environment', env, '--out', token], session=c['session'], org=c['org'])
            restricted = destination / 'restricted.token'
            restricted_id = command(['token', 'create', '--scopes', 'telemetry:read', '--days', '1', '--app', c['app'],
                                     '--environment', env, '--out', restricted], session=c['session'], org=c['org'])['id']
            command(['releases'], token=restricted, org=c['org'], expected=1)
            command(['token', 'revoke', '--id', restricted_id], session=c['session'], org=c['org'])
            command(['app', 'list'], token=restricted, org=c['org'], expected=1)
            cfg = json.loads(config.read_text()); cfg['cloud']['tokenFile'] = str(token); cfg['sourceRevision'] = 'phase9c'
            save(config, cfg)
            source.write_text(baseline.replace('$label baseline', '$label via Cloud'))
            run([cli, 'analyze', config]); run([cli, 'publish', config])
            releases = command(['releases', '--environment', env], token=token, org=c['org'])['items']
            assert len(releases) == 1
            rid = releases[0]['id']
            for _ in range(60):
                release = command(['release', 'inspect', '--id', rid], token=token, org=c['org'])
                if release['status'] == 'ready': break
                time.sleep(0.5)
            else: raise RuntimeError('Local Cloud publication did not complete')
            for action, extra in [('rollout', ['--percentage', '25']), ('pause', []), ('resume', []), ('rollout', ['--percentage', '100'])]:
                release = command(['release', 'inspect', '--id', rid], token=token, org=c['org'])
                command([action, '--id', rid, '--version', str(release['version']), *extra], token=token, org=c['org'])
            release = command(['release', 'inspect', '--id', rid], token=token, org=c['org'])
            op = command(['rollback', '--id', rid, '--version', str(release['version']), '--key', secrets.token_hex(16)], token=token, org=c['org'])
            for _ in range(60):
                operation = command(['operation', 'inspect', '--id', op['id']], token=token, org=c['org'])
                if operation['status'] == 'succeeded': break
                time.sleep(0.5)
            else: raise RuntimeError('Signed rollback did not complete')
            assert operation['receipt']['signatureVerified']
            command(['enrollment', 'ticket', '--environment', env, '--out', destination / 'ticket'], token=token, org=c['org'])
            session = json.loads(c['session'].read_text())
            tenants[label] = dict(org=c['org'], app=c['app'], environment=env, token=token.read_text().strip(),
                headers={'Cookie':session['cookie'], 'Origin':base, 'X-CSRF-Token':session['csrf'],
                         'Dootah-Organization':c['org'], 'Content-Type':'application/json'})
            if label == 'a':
                irs = list((destination / 'build/dootah-publish').glob('run-*/portable-ir.json'))
                shutil.copy(irs[-1], work / 'artifact.json')
        save(work / 'tenants.json', tenants)
        # R8 retention also uses pre-R8 classes; HTTP never enters this non-debug variant.
        source.write_text(baseline)
        run(build[:-1] + [':app:dootahRetainReleaseRelease'])
        release_record = appdir / 'build/outputs/dootah/release/retained'
        r8dir = work / 'r8'; r8dir.mkdir()
        command(['release', 'import', '--record', release_record / 'release.json', '--source-root', appdir, '--config', r8dir / 'publish.json'])
        # The real Cloud auth/scope/CSRF/enrollment/revocation/DB-isolation/signing suites, no missing fixtures.
        test_env = dict(cloud_env, DOOTAH_TEST_ORIGIN=base, DOOTAH_TEST_TENANTS=str(work / 'tenants.json'),
                        DOOTAH_TEST_ARTIFACT=str(work / 'artifact.json'), DOOTAH_TEST_PASSWORD_FILE=str(password_file))
        output = run(['node', '--test', '--test-reporter=tap', *sorted((ROOT / 'v2/cloud/test').glob('*.test.mjs'))], env=test_env, output=True)
        assert '# fail 0' in output and '# skipped 0' in output, output
        save(work / 'result.json', dict(phase='9C', passed=True, runtimeAbi=2, logicAbi=1,
             publicSdk='0.1.0-alpha.1', standaloneTool=True, debugRetention=True, r8Retention=True,
             customerDatabaseWrites=False, manualIds=False, cloudTests=output,
             stageManifestSha256=hashlib.sha256((stage / 'SHA256SUMS').read_bytes()).hexdigest()))
        print(f'PASS — complete local CLI flow, signed publication/rollback and Cloud suites. Evidence: {work}/result.json', flush=True)
    finally:
        if process:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
        for name in [xpname, pgname]: subprocess.run(['docker', 'rm', '-fv', name], capture_output=True)
        subprocess.run(['docker', 'network', 'rm', network], capture_output=True)
        log.close()


if __name__ == '__main__':
    main()
