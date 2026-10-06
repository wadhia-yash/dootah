#!/usr/bin/env python3
"""Run one V2 gate in a new checkout and empty caches. Never publishes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import uuid
import zipfile
from gates import MANIFEST, require, verify, snapshot_reports
from versions import source, stage_versions

ROOT = Path(__file__).resolve().parents[2]
UPSTREAM = 'b46e13569f5734a66ed903f75f51b87b78e80c1b'
CREDENTIAL = re.compile(r'(?i)passw|passphrase|secret|token|api[_-]?key|private key|authorization|cookie|BEGIN [A-Z ]+')
EXCEPTION = re.compile(r'^[A-Za-z_][\w.]*(?:Error|Exception|Exit|Interrupt)\b(?::.*)?$')


class CommandFailed(ValueError):
    """A child command failed; carries only a non-sensitive category, exit code and sanitized excerpt."""
    def __init__(self, category, code, excerpt):
        super().__init__('Command failed: '+category+' (exit '+str(code)+')')
        self.category, self.code, self.excerpt = category, code, excerpt


def category(args):
    """Tool plus repo-relative script/subcommand; never absolute paths, URLs or option values."""
    words = []
    for arg in map(str, args[:2]):
        if arg.startswith(('/', '-')) or '://' in arg: break
        words.append(arg)
    return ' '.join(words) or Path(str(args[0])).name


def sanitize(lines, work):
    """Drop credential-like lines, hide private paths and long tokens, bound the size."""
    places = sorted({str(work), os.path.realpath(work), str(Path.home())}, key=len, reverse=True)
    clean = []
    for line in lines:
        if CREDENTIAL.search(line): continue
        for place in places: line = line.replace(place, '<workspace>' if place != str(Path.home()) else '<home>')
        clean.append(re.sub(r'[A-Za-z0-9+=_-]{32,}', '<redacted>', line.rstrip())[:300])
    return clean[-40:]


def excerpt(text, work):
    """Keep Gradle's failure summary, failed tasks and the final exception line; else a short tail."""
    lines, picked = text.splitlines(), []
    for n, line in enumerate(lines):
        if line.startswith('* What went wrong:'):
            block = lines[n:n+16]
            end = next((i for i, l in enumerate(block) if i and l.startswith('* Try:')), len(block))
            picked += block[:end]
        elif line.startswith('> Task ') and line.endswith(' FAILED'):
            picked.append(line)
    final = [l for l in lines if EXCEPTION.match(l.strip())]
    picked += final[-1:]
    return sanitize(picked or lines[-15:], work)


def resources(work):
    """Free disk and (Linux) memory at failure: Gradle/R8 losses to disk or the OOM killer look alike otherwise."""
    host = dict(diskFreeBytes=shutil.disk_usage(work).free)
    meminfo = Path('/proc/meminfo')
    if meminfo.is_file():
        values = dict(re.findall(r'^(MemTotal|MemAvailable|SwapTotal):\s+(\d+) kB$', meminfo.read_text(), re.M))
        host.update({k[0].lower()+k[1:]+'Bytes': int(v)*1024 for k, v in values.items()})
    return host


GIB = 1024**3
# The fresh-home staged consumer (Gradle home, distribution, Debug/D8 + Release/R8 outputs) measured
# 4.6 GiB in its workspace and 5.4 GiB of free-space loss; require about 30% headroom over that.
CONSUMER_MIN_FREE = 7*GIB
# The only state later stages read: tracked sources, the suite report snapshots, the stage and the verified ZIP.
PROTECTED = ('reports',)


def allocated(path):
    """Allocated bytes under a Dootah-owned path (du-like; never follows symlinks)."""
    if path.is_symlink() or path.is_file(): return path.lstat().st_blocks*512
    total = 0
    for top, dirs, files in os.walk(path):
        for name in dirs+files:
            try: total += os.lstat(os.path.join(top, name)).st_blocks*512
            except OSError: pass
    return total


def usage(point, work):
    """Sanitized disk snapshot: fixed labels and byte counts only, no listings or paths."""
    parts = dict(checkout='source', nodeModules='source/v2/runtime-spike/node_modules', producerGradleHome='gradle-home',
                 npmCache='npm-cache', tmp='tmp', stage='stage', gradleZip='gradle.zip')
    return dict(point=point, freeBytes=shutil.disk_usage(work).free,
                bytes={k: allocated(work/v) for k, v in parts.items() if (work/v).exists()})


def reclaimable(checkout, work):
    """Producer-only state after staging: git-ignored checkout outputs, producer caches and temp files."""
    listed = subprocess.check_output(['git','ls-files','--others','--ignored','--exclude-standard','--directory','-z'],
                                     cwd=checkout).decode().split('\0')
    paths = [checkout/name.rstrip('/') for name in listed if name]
    require(not any(p.relative_to(checkout).parts[0] in PROTECTED for p in paths), 'Refusing to reclaim protected evidence')
    return paths + [work/'gradle-home', work/'npm-cache'] + sorted((work/'tmp').iterdir())


def reclaim(paths):
    for path in paths:
        if path.is_symlink() or path.is_file(): path.unlink()
        elif path.exists(): shutil.rmtree(path)


def require_free(work, needed, purpose):
    free = shutil.disk_usage(work).free
    require(free >= needed, f'Insufficient disk for {purpose}: required >= {needed/GIB:.1f} GiB, available {free/GIB:.1f} GiB')


def failure_receipt(job, sha, stage, error, work, disk=()):
    """Sanitized failure evidence: never only an exception type, never raw logs or credentials."""
    message = str(error) or type(error).__name__
    failure = dict(job=job, sourceCommit=sha, passed=False, stage=stage,
                   error=dict(type=type(error).__name__, message=(sanitize([message], work) or ['<withheld>'])[0]),
                   host=resources(work), disk=list(disk),
                   message='Required gate failed. Raw logs and credentials are intentionally not exported.')
    if isinstance(error, CommandFailed):
        failure['command'] = dict(category=error.category, exitCode=error.code)
        if job == 'sdk' and error.excerpt:
            # SDK build output only; server/security output may contain generated credentials or findings.
            failure['diagnostics'] = error.excerpt
    return failure


def run(job, output, keep=False):
    require(not output.exists(), 'Choose a new evidence directory')
    require(not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT).strip(), 'CI requires a clean source tree')
    sha = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    work = Path(tempfile.mkdtemp(prefix='dootah-9e-')); work.chmod(0o700)
    (work/'tmp').mkdir(mode=0o700)
    checkout = work/'source'
    env = dict(os.environ)
    # Drop inherited app/operator/signing credentials and tool injection points.
    for key in list(env):
        if key.startswith(('DOOTAH_', 'GPG_', 'ORG_GRADLE_PROJECT_')) or key in ['NODE_OPTIONS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','GRADLE_OPTS','GNUPGHOME']:
            del env[key]
    env.update(GRADLE_USER_HOME=str(work/'gradle-home'), npm_config_cache=str(work/'npm-cache'),
               GRADLE_OPTS='-Dorg.gradle.daemon=false -Dorg.gradle.workers.max=2 -Dorg.gradle.caching=false',
               TMPDIR=str(work/'tmp'), CI='true')
    volumes = ['dootah9e-'+uuid.uuid4().hex+'-'+name for name in ['mod','build']]
    env.update(DOOTAH_GO_MOD_VOLUME=volumes[0], DOOTAH_GO_BUILD_VOLUME=volumes[1])
    log = work/'commands.log'
    def command(args, cwd=None, extra=None, logs=()):
        with log.open('ab') as stream:
            start = stream.tell()
            process = subprocess.Popen(list(map(str,args)),cwd=cwd or checkout,env=dict(env,**(extra or {})),stdout=stream,stderr=stream,start_new_session=True)
            try:
                code=process.wait()
            except BaseException:
                os.killpg(process.pid,signal.SIGTERM)
                process.wait(timeout=60)
                raise
        if code != 0:
            # Excerpt this command's own output plus any build log it names; the raw text stays private.
            texts = [log.read_bytes()[start:].decode('utf-8','replace')]
            texts += [Path(p).read_text('utf-8','replace') for p in logs if Path(p).is_file()]
            raise CommandFailed(category(args), code, sum((excerpt(text, work) for text in texts), [])[-40:])
    current = 'starting'
    disk = []
    def snapshot(point):
        disk.append(usage(point, work))
        return disk[-1]['freeBytes']
    def status(message):
        nonlocal current
        current = message
        free = snapshot(message)
        print(job+': '+message+f' (free disk {free/GIB:.1f} GiB)',flush=True)
    try:
        status('creating isolated checkout and empty caches')
        command(['git','clone','--no-local','--no-checkout',ROOT,checkout],cwd=work)
        command(['git','checkout','--detach',sha])
        source(checkout)
        reports=checkout/'reports';reports.mkdir()
        if job=='server':
            command(['npm','ci','--ignore-scripts'],cwd=checkout/'v2/cloud')
        specs=json.loads(MANIFEST.read_text())['suites']
        for spec in specs:
            if spec['job']==job and spec.get('command'):
                status('running '+spec['id'])
                with (checkout/spec['report']).open('wb') as stream:
                    result=subprocess.run(spec['command'],cwd=checkout,env=env,stdout=stream,stderr=subprocess.STDOUT)
                require(result.returncode==0,'Required Node suite failed: '+spec['id'])
        if job=='sdk':
            status('downloading and verifying the pinned upstream Gradle distribution')
            distribution=work/'gradle.zip'
            command(['curl','--fail','--silent','--show-error','--location','--retry','3','--retry-all-errors',
                     '--connect-timeout','20','--max-time','300',
                     'https://services.gradle.org/distributions/gradle-9.3.1-bin.zip','-o',distribution])
            sys.path.insert(0,str(checkout/'v2/android-sdk/release'))
            from consumer import configure_distribution
            for wrapper in ['v2/runtime-spike/android','v2/native-consumer']:
                configure_distribution(checkout/wrapper/'gradle/wrapper/gradle-wrapper.properties',distribution)
            env['DOOTAH_GRADLE_DISTRIBUTION_ZIP']=str(distribution)
            status('building SDK, runtime/health/plugin tests and real Compose gate')
            command(['bash','v2/android-sdk/build-sdk.sh'])
            status('running publisher JVM and packaging regressions')
            command(['v2/native-consumer/gradlew','-p','v2/publishing','test','--no-build-cache','--rerun-tasks'])
            command(['python3','v2/ci/unittest_report.py','v2/android-sdk/release',reports/'packaging.xml'])
            snapshot_reports(job,checkout)
            verify(job,checkout)
            for variant,n in [('debug',13),('release',11)]:
                hooks=json.loads((checkout/f'v2/native-consumer/app/build/outputs/dootah/{variant}/hooks.json').read_text())
                require(hooks['hooks']>=n and hooks['sdkVersion']==source(checkout)['developmentVersion'],'Invalid development hooks')
            stage=work/'stage'
            status('staging public Maven candidate with license/content audit')
            metadata={k:os.environ[k] for k in ['DOOTAH_DEVELOPER_ID','DOOTAH_DEVELOPER_NAME'] if os.environ.get(k)}
            command(['bash','v2/android-sdk/release/stage.sh',stage],extra=metadata)
            # Producer phases are complete and their reports snapshotted; the consumer builds from the stage
            # with its own fresh Gradle home, so producer caches/outputs only consume the runner's disk.
            status('reclaiming producer-only workspace before isolated consumer')
            reclaim(reclaimable(checkout,work))
            status('building isolated staged consumer Debug/D8 and Release/R8')
            require_free(work,CONSUMER_MIN_FREE,'isolated staged consumer')
            command(['python3','v2/android-sdk/release/consumer.py',stage],logs=[stage/'consumer.log'])
            snapshot('after isolated staged consumer')
            versions=stage_versions(checkout,stage)
        elif job=='server':
            status('fetching pinned xprem and building audited containers')
            upstream=work/'xprem'
            command(['git','init',upstream])
            command(['git','-C',upstream,'remote','add','origin','https://github.com/mercuretechnologies/xprem.git'])
            command(['git','-C',upstream,'fetch','--depth=1','origin',UPSTREAM])
            command(['git','-C',upstream,'checkout','--detach',UPSTREAM])
            command(['bash','v2/deploy/build.sh',upstream],extra={'DOOTAH_BUILD_EVIDENCE':str(reports)})
            audit=json.loads((reports/'audit.json').read_text())
            require(audit['unchangedUpstreamFilesVerified']==341 and not audit['eeDependencies'] and audit['packages']>0 and audit['sourceFiles']>0,'Incomplete xprem graph audit')
            images=[]
            for name in ['dootah-cloud:9d','dootah-xprem:9d']:
                data=json.loads(subprocess.check_output(['docker','image','inspect',name]))[0]
                require(data['Os']=='linux' and data['Architecture']=='amd64','Server CI requires Linux AMD64 images')
                images.append(dict(name=name,id=data['Id'],architecture=data['Architecture'],os=data['Os']))
            (reports/'images.json').write_text(json.dumps(images,indent=2)+'\n')
            status('validating production Caddy without network')
            # Pinned Caddy image is shared with production Compose.
            compose=(checkout/'v2/deploy/compose.yaml').read_text()
            caddy=re.search(r'image: (caddy:[^\s]+)',compose).group(1)
            postgres=re.search(r'image: (postgres:[^\s]+)',compose).group(1)
            for image in [caddy,postgres]:
                command(['docker','pull','--platform','linux/amd64',image])
            command(['docker','run','--rm','--network=none','-e','DOOTAH_SITE=ci.example.invalid',
                     '-v',str(checkout/'v2/deploy/Caddyfile')+':/etc/caddy/Caddyfile:ro',caddy,
                     'caddy','validate','--config','/etc/caddy/Caddyfile','--adapter','caddyfile'])
            status('running disposable HTTPS Cloud integration, deployment and fresh-volume restore')
            command(['python3','v2/deploy/acceptance.py'],extra={'DOOTAH_ACCEPTANCE_EVIDENCE':str(reports)})
        elif job=='security':
            status('running CI failure-path regressions')
            command(['python3','v2/ci/unittest_report.py','v2/ci',reports/'ci.xml'])
            status('building pinned scanner/linter tools')
            command(['docker','build','-t','dootah-ci-tools:9e','-f','v2/ci/Tools.Dockerfile','v2/ci'])
            command(['docker','run','--rm','-v',str(checkout)+':/src:ro','dootah-ci-tools:9e',
                     'actionlint','-shellcheck=','-pyflakes=','.github/workflows/v2-ci.yml','.github/workflows/v2-gates.yml','.github/workflows/v2-release.yml'])
            # Export every tracked byte (including historical V1); no ignored local files or Git credentials.
            scan=work/'scan';scan.mkdir()
            tracked=subprocess.check_output(['git','ls-files','-z'],cwd=checkout).decode().split('\0')
            for name in filter(None,tracked):
                dest=scan/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(checkout/name,dest)
            command(['python3','v2/ci/scan_secrets.py',scan])
        suites=verify(job,checkout)
        output.mkdir(parents=True)
        receipt=dict(schema=1,job=job,sourceCommit=sha,suites=suites,passed=True,
                     freshCheckout=True,emptyDependencyCaches=True,externalPublication=False,disk=disk)
        if job=='sdk':
            receipt['versions']=versions
            (stage/'candidate.json').write_text(json.dumps(dict(versions,sourceCommit=sha,signed=False),indent=2)+'\n')
            # Explicit export allowlist: no raw Gradle logs, private files or build intermediates.
            with zipfile.ZipFile(output/'candidate.zip','w',zipfile.ZIP_DEFLATED) as archive:
                for name in ['repository','SHA256SUMS','inventory.json','audit.json','consumer-result.json','candidate.json']:
                    path=stage/name
                    for file in (sorted(path.rglob('*')) if path.is_dir() else [path]):
                        if file.is_file(): archive.write(file,file.relative_to(stage).as_posix())
            for name in ['SHA256SUMS','inventory.json','candidate.json']:
                shutil.copy2(stage/name,output/name)
            (output/'candidate.zip.sha256').write_text(hashlib.sha256((output/'candidate.zip').read_bytes()).hexdigest()+'  candidate.zip\n')
        if job=='server':
            for name in ['audit.json','images.json','acceptance.json']:
                shutil.copy2(reports/name,output/name)
        (output/'result.json').write_text(json.dumps(receipt,indent=2)+'\n')
        status('PASS; sanitized evidence exported')
    except BaseException as error:
        output.mkdir(parents=True,exist_ok=True)
        try:
            failure=failure_receipt(job,sha,current,error,work,disk)
        except Exception:
            # Diagnostics must never cost the receipt itself.
            failure=dict(job=job,sourceCommit=sha,passed=False,stage=current,error=dict(type=type(error).__name__,message='<withheld>'),
                         message='Required gate failed. Raw logs and credentials are intentionally not exported.')
        (output/'failure.json').write_text(json.dumps(failure,indent=2)+'\n')
        print(job+': FAILED during "'+current+'": '+failure['error']['type']+': '+failure['error']['message'],file=sys.stderr,flush=True)
        raise
    finally:
        if job=='server':
            for volume in volumes:
                subprocess.run(['docker','volume','rm','-f',volume],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if keep:
            print('Private local workspace retained at '+str(work),flush=True)
        else:
            shutil.rmtree(work)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('job',choices=['sdk','server','security'])
    parser.add_argument('output',type=Path)
    parser.add_argument('--keep-private',action='store_true',help='Local debugging only; never upload the private workspace')
    args=parser.parse_args()
    signal.signal(signal.SIGTERM,lambda *_: sys.exit(143))
    try:
        run(args.job,args.output.resolve(),args.keep_private)
    except Exception as error:
        print('V2 gate FAILED: '+type(error).__name__+'; private command output suppressed.',file=sys.stderr)
        sys.exit(1)
