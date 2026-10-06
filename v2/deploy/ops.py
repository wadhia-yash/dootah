#!/usr/bin/env python3
"""Host-authenticated alpha operator commands. Never prints credential contents."""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys

HERE = Path(__file__).resolve().parent

def read_env(path):
    result = {}
    for line in path.read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            k, v = line.split('=', 1)
            result[k.strip()] = v.strip()
    return result

def private(path, value):
    with open(path, 'x', opener=lambda p,f: os.open(p, f, 0o600)) as f:
        f.write(value)

def generate(directory):
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    for name in ['postgres-password', 'cloud-owner-password', 'cloud-runtime-password', 'xprem-owner-password', 'xprem-runtime-password', 'xprem-jwt']:
        private(directory / name, secrets.token_hex(32))
    private(directory / 'initial-password', 'Aa1!' + secrets.token_hex(28))
    private(directory / 'master-key', base64.b64encode(secrets.token_bytes(32)).decode())
    private(directory / 'bindings.json', '{}\n')
    for service in ['cloud', 'xprem']:
        for role in ['owner', 'runtime']:
            pw = (directory / f'{service}-{role}-password').read_text()
            private(directory / f'{service}-{role}-url', f'postgresql://{service}_{role}:{pw}@postgres:5432/{service}?sslmode=disable')

def compose(envfile, *args, data=None, capture=False, check=True):
    return subprocess.run(['docker','compose','--env-file',str(envfile),'-f',str(HERE/'compose.yaml'),*map(str,args)],
        input=data, stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None, check=check)

def sql(envfile, database, text):
    r = compose(envfile, 'exec','-T','postgres','psql','-X','-q','-t','-A','-U','postgres','-d',database,'-v','ON_ERROR_STOP=1',
                data=text.encode(), capture=True)
    return r.stdout

def bootstrap(envfile):
    env = read_env(envfile); directory = Path(env['DOOTAH_SECRETS_DIR'])
    text = "SET log_statement='none'; SET log_min_error_statement='panic';\n"
    text += "SELECT 'CREATE ROLE dootah_cloud_runtime NOLOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='dootah_cloud_runtime')\\gexec\n"
    for service in ['cloud','xprem']:
        for role in ['owner','runtime']:
            name = f'{service}_{role}'
            pw = (directory/f'{service}-{role}-password').read_text().strip()
            if len(pw) < 24 or not all(c in '0123456789abcdef' for c in pw): raise ValueError('invalid generated database credential file')
            text += f"SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L','{name}','{pw}') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='{name}')\\gexec\n"
        text += f"SELECT 'CREATE DATABASE {service} OWNER {service}_owner' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='{service}')\\gexec\n"
        text += f"REVOKE ALL ON DATABASE {service} FROM PUBLIC; GRANT CONNECT ON DATABASE {service} TO {service}_owner,{service}_runtime;\n"
    text += 'GRANT dootah_cloud_runtime TO cloud_runtime;\n'
    sql(envfile, 'postgres', text)
    for service in ['cloud','xprem']:
        sql(envfile, service, 'REVOKE CREATE ON SCHEMA public FROM PUBLIC;')

def grant_xprem(envfile):
    sql(envfile, 'xprem', '''GRANT USAGE ON SCHEMA public TO xprem_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO xprem_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO xprem_runtime;
REVOKE INSERT,UPDATE,DELETE ON goose_db_version FROM xprem_runtime;
''')

def backup(envfile, target):
    target.mkdir(mode=0o700, parents=True, exist_ok=False)
    # Explicit maintenance window. On failure leave writers stopped for inspection.
    compose(envfile,'stop','proxy','cloud','xprem')
    for db in ['cloud','xprem']:
        dump = compose(envfile,'exec','-T','postgres','pg_dump','-U','postgres','-Fc',db,capture=True).stdout
        p = target/f'{db}.dump'; p.write_bytes(dump); p.chmod(0o600)
    result = compose(envfile,'run','--rm','--no-deps','-T','--entrypoint','tar','xprem','-C','/state','-czf','-','.',capture=True)
    (target/'xprem-state.tgz').write_bytes(result.stdout); (target/'xprem-state.tgz').chmod(0o600)
    shutil.copytree(Path(read_env(envfile)['DOOTAH_SECRETS_DIR']),target/'secrets')
    shutil.copy2(Path(read_env(envfile)['DOOTAH_CADDYFILE']),target/'Caddyfile');
    shutil.copy2(HERE/'compose.yaml',target/'compose.yaml');
    shutil.copy2(envfile,target/'deploy.env'); (target/'deploy.env').chmod(0o600)
    # Images and source are regeneratable, but record the exact image IDs for restore.
    images = compose(envfile,'images','--format','json',capture=True).stdout
    (target/'images.json').write_bytes(images)
    compose(envfile,'up','-d','xprem','cloud','proxy')

def restore(envfile, source):
    # Refuse existing application schema; operator selects a fresh Compose project.
    for db in ['cloud','xprem']:
        count = sql(envfile,db,"SELECT count(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema');")
        if count.decode().strip() != '0': raise ValueError('restore requires empty databases')
    for db in ['cloud','xprem']:
        compose(envfile,'exec','-T','postgres','pg_restore','--exit-on-error','--no-owner','--role',f'{db}_owner','-U','postgres','-d',db,data=(source/f'{db}.dump').read_bytes(),capture=True)
    grant_xprem(envfile)
    compose(envfile,'run','--rm','--no-deps','-T','--entrypoint','sh','xprem','-ec',
        'test -z "$(ls -A /state)"; tar -C /state -xzf -; chown -R 10001:10001 /state',data=(source/'xprem-state.tgz').read_bytes(),capture=True)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--env',type=Path)
    p.add_argument('command',choices=['secrets','bootstrap','grant-xprem','backup','restore','bindings-list','binding-put'])
    p.add_argument('args',nargs='*')
    a=p.parse_args()
    if a.command == 'secrets': generate(Path(a.args[0])); return
    if not a.env: p.error('--env required')
    if a.command == 'bootstrap': bootstrap(a.env)
    elif a.command == 'grant-xprem': grant_xprem(a.env)
    elif a.command == 'backup': backup(a.env,Path(a.args[0]))
    elif a.command == 'restore': restore(a.env,Path(a.args[0]))
    elif a.command == 'bindings-list':
        data=json.loads((Path(read_env(a.env)['DOOTAH_SECRETS_DIR'])/'bindings.json').read_text())
        print(json.dumps({k:{f:v[f] for f in ['appId','base','publicOrigin']} for k,v in data.items()},indent=2))
    elif a.command == 'binding-put':
        # Use the packaged Cloud validation + DB identity check. Files are only
        # writable by the local authenticated operator; no customer API is added.
        app, credential = a.args
        env=read_env(a.env); dest=Path(env['DOOTAH_SECRETS_DIR'])/'bindings.json'
        old=dest.read_text(); bindings=json.loads(old); provision=json.loads(Path(credential).read_text())
        b={k:provision[k] for k in ['appId','apiKey','certificate']}
        b.update(base='http://xprem:3100',publicOrigin=env['DOOTAH_CLOUD_ORIGIN'])
        if app in bindings and (bindings[app]['appId'] != b['appId'] or bindings[app]['certificate'] != b['certificate']):
            raise ValueError('binding replacement must preserve installed signing identity')
        bindings[app]=b
        candidate=dest.with_name('bindings.candidate'); private(candidate,json.dumps(bindings))
        try:
            code='''import {loadBindings} from './config.mjs'; import {pool} from './core.mjs';
try { const b=loadBindings(); for(const id of Object.keys(b)) { if(!(await pool.query('SELECT id FROM apps WHERE id=$1',[id])).rowCount) throw Error();
const item=b[id], r=await fetch(item.base+'/'+item.appId+'/cloudBinding',{headers:{Authorization:'Bearer '+item.apiKey},redirect:'error',signal:AbortSignal.timeout(5000)});
if(!r.ok) throw Error(); const actual=await r.json(); if(actual.appId!==item.appId || actual.certificate!==item.certificate) throw Error(); }
} catch { console.error('binding validation failed'); process.exitCode=1; } finally { await pool.end(); }'''
            compose(a.env,'run','--rm','--no-deps','-T','-e','DOOTAH_DELIVERY_BINDINGS=/run/private/bindings.candidate','-v',f'{candidate.resolve()}:/run/input/bindings.candidate:ro','cloud','node','--input-type=module','-e',code,capture=True)
            os.replace(candidate,dest)
        finally: candidate.unlink(missing_ok=True)
        # Secret files are snapshotted at process startup. Recreate after atomic replace.
        compose(a.env,'up','-d','--force-recreate','cloud')
    print('Completed')

if __name__=='__main__':
    try: main()
    except (Exception, KeyboardInterrupt):
        print('Operator command failed; check command inputs, private file permissions and service health. Secret values suppressed.',file=sys.stderr)
        sys.exit(1)
