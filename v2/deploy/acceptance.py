#!/usr/bin/env python3
"""Disposable production-mode Compose proof. Publishes loopback ports only; no public ACME."""
import hashlib, json, os, re, secrets, shutil, signal, socket, subprocess, tempfile, time
from pathlib import Path
import ops

HERE=Path(__file__).resolve().parent

def port():
    with socket.socket() as s: s.bind(('127.0.0.1',0)); return s.getsockname()[1]

EDGE_PROBE='''
import https from "node:https";
import { networkInterfaces } from "node:os";
import { randomUUID } from "node:crypto";
const forged = process.argv[1];
const send = (method, path, headers, body) => new Promise((resolve, reject) => {
  const r = https.request({ host: "proxy", port: 443, method, path, ca: process.env.DOOTAH_PROBE_CA,
    headers: { "X-Forwarded-For": forged, Forwarded: "for=" + forged, "X-Real-IP": forged, ...headers } },
    (res) => { res.resume(); res.on("end", () => resolve(res.statusCode)); });
  r.on("error", reject);
  r.end(body);
});
const manifest = await send("GET", "/manifest", { "expo-app-id": randomUUID(), "eas-client-id": randomUUID() });
const body = JSON.stringify({ email: randomUUID() + "@edge.test", password: "not-a-password" });
const session = await send("POST", "/v1/sessions", { Origin: "https://proxy", "Content-Type": "application/json" }, body);
const address = Object.values(networkInterfaces()).flat().find((i) => i.family === "IPv4" && !i.internal).address;
console.log(JSON.stringify({ address, manifest, session }));
'''

def main():
    work=Path(tempfile.mkdtemp(prefix='dootah-9d-')); work.chmod(0o700)
    print(f'Private acceptance workspace: {work}',flush=True)
    envfile=work/'deploy.env'; secret_dir=work/'secrets'; ops.generate(secret_dir)
    project='dootah9d'+secrets.token_hex(4)
    http,https=port(),port()
    # A fresh ingress range per run also exercises the operator override of the default.
    ingress=f'10.231.{secrets.randbelow(256)}'
    envfile.write_text(f'''COMPOSE_PROJECT_NAME={project}
DOOTAH_SITE=proxy
DOOTAH_CLOUD_ORIGIN=https://proxy
DOOTAH_OPERATOR_EMAIL=operator@local.test
DOOTAH_SECRETS_DIR={secret_dir}
DOOTAH_CADDYFILE={HERE}/Caddyfile.local
DOOTAH_BIND_IP=127.0.0.1
DOOTAH_HTTP_PORT={http}
DOOTAH_HTTPS_PORT={https}
DOOTAH_INGRESS_PREFIX={ingress}
''');envfile.chmod(0o600)
    log=(work/'commands.log').open('wb'); log_path=work/'commands.log';log_path.chmod(0o600)
    projects=[envfile]
    checks=[]
    def c(*args,data=None,check=True):
        r=ops.compose(envfile,*args,data=data,capture=True,check=False)
        log.write(r.stderr);log.flush()
        if check and r.returncode: raise RuntimeError('Compose command failed: '+str(args[:3]))
        return r
    def ok(name): checks.append(name); print('PASS '+name,flush=True)
    def wait(service):
        for _ in range(90):
            cid=c('ps','-q',service).stdout.decode().strip()
            if cid:
                r=subprocess.run(['docker','inspect','--format','{{.State.Health.Status}}',cid],capture_output=True,text=True)
                if r.stdout.strip()=='healthy':return
            time.sleep(1)
        raise RuntimeError(service+' not healthy')
    def copy_inputs():
        for local,remote in [(HERE/'acceptance-client.mjs','/tmp/client.mjs'),(work/'root.crt','/tmp/root.crt'),(secret_dir/'initial-password','/tmp/initial-password')]:
            c('exec','-T','-u','0','cloud','sh','-c','umask 077; cat > "$1"; chown 10001:10001 "$1"','sh',remote,data=local.read_bytes())
        for name in ['tenants.json','artifact.json']:
            if (work/name).exists():
                c('exec','-T','-u','0','cloud','sh','-c','umask 077; cat > "$1"; chown 10001:10001 "$1"','sh','/tmp/'+name,data=(work/name).read_bytes())
    def client(mode):
        c('exec','-T','-u','10001','-e','NODE_EXTRA_CA_CERTS=/tmp/root.crt','cloud','node','/tmp/client.mjs',mode)
    def start():
        c('up','-d','xprem','cloud');wait('xprem');wait('cloud');c('up','-d','proxy')
        for _ in range(60):
            r=c('cp','proxy:/data/caddy/pki/authorities/local/root.crt',work/'root.crt',check=False)
            if not r.returncode:break
            time.sleep(1)
        else:raise RuntimeError('local TLS CA unavailable')
        copy_inputs()
        c('exec','-T','-u','10001','-e','NODE_EXTRA_CA_CERTS=/tmp/root.crt','cloud','node','-e',"fetch('https://proxy/ready').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))")
    def edge_probe(config):
        # Two concurrent Internet-side clients on the edge network, each forging every
        # forwarding header. Cloud must key both limits on each client's own address.
        names=[f'{project}-edge-{n}' for n in 'ab']
        ca=(work/'root.crt').read_text()
        try:
            for name in names:
                subprocess.run(['docker','run','-d','--rm','--name',name,'--network',config['networks']['edge']['name'],
                                '--user','10001:10001','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true',
                                '--entrypoint','node',config['services']['cloud']['image'],'-e','setTimeout(()=>{},300000)'],
                               check=True,stdout=log,stderr=log)
            probes=[]
            for n,name in enumerate(names):
                forged=f'203.0.113.{10+n}'
                out=subprocess.run(['docker','exec','-e','DOOTAH_PROBE_CA='+ca,name,'node','--input-type=module','-e',EDGE_PROBE,forged],
                                   check=True,capture_output=True).stdout
                probes.append(dict(json.loads(out),forged=forged))
        finally:
            subprocess.run(['docker','rm','-f',*names],stdout=log,stderr=log)
        keys=set(c('exec','-T','postgres','psql','-X','-At','-U','postgres','-d','cloud','-c','SELECT key FROM dootah_cloud.rate_limits').stdout.decode().split())
        has=lambda kind,ip: hashlib.sha256(f'{kind}:{ip}'.encode()).hexdigest() in keys
        assert len({p['address'] for p in probes})==2
        for p in probes:
            assert p['manifest']==404 and p['session']==401, p
            for kind in ['delivery','login-ip']:
                assert has(kind,p['address']) and not has(kind,p['forged'])
                assert not has(kind,config['services']['cloud']['environment']['DOOTAH_TRUSTED_PROXIES'])
    try:
        config=json.loads(c('config','--format','json').stdout)
        for service in ['cloud','xprem','postgres']:
            assert not config['services'][service].get('ports')
        assert config['networks']['backend']['internal'] and config['networks']['ingress']['internal']
        assert set(config['services']['proxy']['networks'])=={'edge','ingress'}
        assert all(p['host_ip']=='127.0.0.1' for p in config['services']['proxy']['ports'])
        ok('Compose private topology and loopback-only acceptance proxy')
        c('up','-d','postgres');wait('postgres');ok('PostgreSQL healthy')
        ops.bootstrap(envfile);ops.bootstrap(envfile)
        c('run','--rm','cloud-migrate');c('run','--rm','cloud-migrate')
        c('run','--rm','xprem-migrate');ops.grant_xprem(envfile)
        ok('idempotent bootstrap and explicit migrations')
        for email in ['owner-a@local.test','owner-b@local.test','viewer@local.test']:
            c('run','--rm','cloud-user','node','admin.mjs','user',email)
        start();ok('xprem healthy, Cloud ready, trusted local HTTPS proxy')
        # Verify effective non-root HTTP processes and no host port publishing.
        for service in ['cloud','xprem']:
            status=c('exec','-T',service,'cat','/proc/1/status').stdout.decode()
            assert re.search(r'Uid:\s+10001\s+10001',status)
            c('exec','-T','-u','10001',service,'sh','-ec',
              'test "$(stat -c %a /run/private)" = 700; '
              'for p in /run/private/*; do test "$(stat -c %a "$p")" = 600; test -r "$p"; done')
            c('exec','-T','-u','10002',service,'sh','-ec',
              'test ! -r /run/private; test ! -x /run/private; test ! -x /run/input')
        ok('runtime secret snapshots stay private to UID 10001')
        for service in ['postgres','xprem','cloud']:
            cid=c('ps','-q',service).stdout.decode().strip()
            info=json.loads(subprocess.check_output(['docker','inspect',cid]))[0]
            assert not info['HostConfig']['PortBindings']
        ok('non-root HTTP processes and no host ports for private services')
        edge_probe(config)
        ok('real Caddy path attributes distinct edge clients and replaces forged forwarding headers')
        client('setup')
        for name in ['tenants.json','artifact.json']:
            (work/name).write_bytes(c('exec','-T','-u','10001','cloud','cat','/tmp/'+name).stdout);(work/name).chmod(0o600)
        tenants=json.loads((work/'tenants.json').read_text())
        ok('authenticated org/app/environment creation and contract registration')
        for label,t in tenants.items():
            raw=c('run','--rm','--no-deps','-T','xprem','-provision','-app-name','Acceptance '+label,'-channel','development').stdout
            credentials=work/(label+'.credentials.json');credentials.write_bytes(raw);credentials.chmod(0o600)
            subprocess.run(['python3',str(HERE/'ops.py'),'--env',str(envfile),'binding-put',t['app'],str(credentials)],stdout=log,stderr=log,check=True)
            wait('cloud')
        copy_inputs();client('publish');ok('operator provisioning, validated bindings, signed publish and rollback')
        # Replacement preserves trust identity; invalid credentials never replace the file.
        old=(secret_dir/'bindings.json').read_bytes(); a=tenants['a']; cred=json.loads((work/'a.credentials.json').read_text())
        invalid=work/'invalid.credentials.json'; invalid.write_text(json.dumps(dict(cred,apiKey=secrets.token_hex(32))));invalid.chmod(0o600)
        rejected=subprocess.run(['python3',str(HERE/'ops.py'),'--env',str(envfile),'binding-put',a['app'],str(invalid)],stdout=log,stderr=log)
        assert rejected.returncode and (secret_dir/'bindings.json').read_bytes()==old
        metadata=json.loads(c('run','--rm','--no-deps','-T','xprem','-key-app',cred['appId'],'-key-action','list').stdout)
        assert len(metadata)==1
        rotated=work/'rotated.credentials.json'; rotated.write_bytes(c('run','--rm','--no-deps','-T','xprem','-key-app',cred['appId'],'-key-action','create').stdout);rotated.chmod(0o600)
        subprocess.run(['python3',str(HERE/'ops.py'),'--env',str(envfile),'binding-put',a['app'],str(rotated)],stdout=log,stderr=log,check=True)
        wait('cloud');copy_inputs();client('publish')
        c('run','--rm','--no-deps','-T','xprem','-key-app',cred['appId'],'-key-action','revoke','-key-id',metadata[0]['id'])
        ok('invalid binding leaves prior config intact; API-key rotation preserves signing identity')
        # A delivery outage must not turn Cloud globally unready.
        c('stop','xprem')
        c('exec','-T','-u','10001','cloud','node','-e',"fetch('http://127.0.0.1:3100/ready').then(r=>process.exit(r.ok?0:1))")
        c('start','xprem');wait('xprem')
        # Liveness remains independent of PostgreSQL; readiness fails closed.
        c('stop','postgres');time.sleep(6)
        c('exec','-T','-u','10001','cloud','node','--input-type=module','-e',"const live=await fetch('http://127.0.0.1:3100/live');const ready=await fetch('http://127.0.0.1:3100/ready');if(live.status!==200||ready.status!==503)process.exit(1)")
        c('start','postgres');wait('postgres');wait('cloud');wait('xprem')
        ok('liveness survives DB failure; readiness rejects DB failure and tolerates xprem outage')

        # Actual packaged HTTP runtime rejects migration credentials before listening.
        result=c('run','--rm','--no-deps','-T','-e','DOOTAH_CLOUD_HOST=0.0.0.0','-e','DOOTAH_CLOUD_ORIGIN=https://proxy','-e','DOOTAH_XPREM_ORIGIN=http://xprem:3100','-e','DOOTAH_DELIVERY_BINDINGS=/run/private/bindings.json','-v',f'{secret_dir}/bindings.json:/run/input/bindings.json:ro','cloud-migrate','node','server.mjs',check=False)
        assert result.returncode and b'overprivileged' in result.stderr
        for db in ['cloud','xprem']:
            r=c('exec','-T','postgres','psql','-X','-U','postgres','-d',db,'-v','ON_ERROR_STOP=1',data=f'SET ROLE {db}_runtime; CREATE TABLE {"dootah_cloud" if db=="cloud" else "public"}.forbidden(id int);'.encode(),check=False)
            assert r.returncode and b'permission denied' in r.stderr
        ok('runtime roles cannot migrate and HTTP rejects migration owner')
        for name,value in [('DOOTAH_CLOUD_ORIGIN','http://proxy'),('DOOTAH_JAVA','/missing'),('DOOTAH_CLOUD_DATABASE_URL_FILE','/missing')]:
            bad=c('run','--rm','--no-deps','-T','-e',name+'='+value,'cloud',check=False)
            assert bad.returncode
        ok('packaged production startup rejects HTTP, missing DB and unavailable Java')

        result=c('exec','-T','-u','10001','-e','NODE_EXTRA_CA_CERTS=/tmp/root.crt','-e','DOOTAH_TEST_ORIGIN=https://proxy','-e','DOOTAH_TEST_TENANTS=/tmp/tenants.json','-e','DOOTAH_TEST_ARTIFACT=/tmp/artifact.json','-e','DOOTAH_TEST_PASSWORD_FILE=/tmp/initial-password','cloud','node','--test','--test-reporter=tap','test/api.test.mjs','test/core.test.mjs','test/database.test.mjs','test/release.test.mjs','test/client.test.mjs','test/proxy.test.mjs')
        tap=result.stdout.decode();(work/'cloud-tests.tap').write_text(tap);assert '# fail 0' in tap and '# skipped 0' in tap
        cloud_passed=int(re.search(r'^# pass (\d+)$',tap,re.M).group(1))
        (work/'cloud-tests.tap').write_text(tap);ok('all Cloud tests including database privileges and signed backend')
        c('restart','postgres','xprem','cloud');wait('postgres');wait('xprem');wait('cloud');copy_inputs();client('verify');ok('restart preserves state and signed delivery')
        logs=c('logs','--no-color').stdout
        values=[]
        for f in secret_dir.iterdir():
            if f.name not in ['bindings.json'] and not f.name.endswith('-url'):values.append(f.read_text().strip())
        values += [v['apiKey'] for v in json.loads((secret_dir/'bindings.json').read_text()).values()]
        values += [json.loads((work/(label+'.credentials.json')).read_text())['apiKey'] for label in ['a','b']]
        values += [v['token'] for v in tenants.values()]
        values += [v['headers']['Cookie'].split('=',1)[1] for v in tenants.values()]
        assert all(v.encode() not in logs for v in values)
        ok('credentials absent from service logs')
        state_before=sorted(c('exec','-T','xprem','find','/state','-type','f','-exec','sha256sum','{}','+').stdout.decode().splitlines())
        backup=work/'backup';ops.backup(envfile,backup)
        c('down')
        restored=work/'restore.env';restored.write_text(envfile.read_text().replace(project,project+'restore').replace(str(secret_dir),str(backup/'secrets')));restored.chmod(0o600)
        envfile=restored;secret_dir=backup/'secrets';projects.append(restored)
        c('up','-d','postgres');wait('postgres');ops.bootstrap(envfile);ops.restore(envfile,backup)
        start();client('verify')
        state_after=sorted(c('exec','-T','xprem','find','/state','-type','f','-exec','sha256sum','{}','+').stdout.decode().splitlines())
        assert state_before==state_after
        assert all(v.encode() not in c('logs','--no-color').stdout for v in values)
        ok('fresh-volume database/storage/key/config restore with identical storage hashes and verified signatures')
        receipt={'phase':'9D','passed':True,'restorePassed':True,'checks':checks,'cloudTests':{'passed':cloud_passed,'failed':0,'skipped':0},'runtimeAbi':2,'logicAbi':1,'externalDeployment':False}
        (work/'acceptance.json').write_text(json.dumps(receipt,indent=2))
        print(f'PASS receipt: {work}/acceptance.json',flush=True)
    finally:
        cleanup = [ops.compose(e,'down','-v','--remove-orphans',capture=True,check=False).returncode for e in projects]
        log.close()
        evidence = os.environ.get('DOOTAH_ACCEPTANCE_EVIDENCE')
        try:
            if any(cleanup):
                raise RuntimeError('Acceptance resource cleanup failed')
            if evidence and (work/'acceptance.json').exists():
                target = Path(evidence); target.mkdir(parents=True,exist_ok=True)
                receipt=json.loads((work/'acceptance.json').read_text())
                receipt['cleanupPassed']=True
                (target/'acceptance.json').write_text(json.dumps(receipt,indent=2)+'\n')
                # The CI caller keeps this raw test report private and exports counts only.
                shutil.copy2(work/'cloud-tests.tap',target/'cloud-tests.tap')
        finally:
            if evidence:
                shutil.rmtree(work)

if __name__=='__main__':
    def interrupted(*_): raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM,interrupted)
    main()
