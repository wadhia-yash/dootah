#!/usr/bin/env python3
"""Offline audit of built production images and their real privilege-drop entrypoint."""
import json
import os
from pathlib import Path
import subprocess
import tempfile


def run(*args, check=True):
    result = subprocess.run(list(args), capture_output=True, text=True)
    if check and result.returncode:
        raise RuntimeError('Image permission audit command failed: ' + args[0])
    return result


def audit(image, cloud):
    roots = ['/app', '/licenses', '/entrypoint.sh'] if cloud else ['/licenses', '/dootah-server', '/entrypoint.sh']
    output = run('docker', 'run', '--rm', '--network=none', '--entrypoint', 'find', image,
                 *roots, '(', '-type', 'f', '-o', '-type', 'd', ')', '-exec',
                 'stat', '-c', '%a|%u|%g|%F|%n', '{}', '+').stdout
    files = {}
    for line in output.splitlines():
        mode, uid, gid, kind, path = line.split('|', 4)
        assert (uid, gid) == ('0', '0'), f'Immutable payload owner: {path}'
        assert mode in (['755'] if kind == 'directory' else ['644', '755']), f'Payload mode: {path}'
        files[path] = mode
    assert files['/entrypoint.sh'] == '755'
    assert run('docker', 'run', '--rm', '--network=none', '--entrypoint', 'stat', image,
               '-c', '%a:%u:%g', '/run/input').stdout.strip() == '700:0:0'
    if cloud:
        for path in ['admin.mjs', 'server.mjs', 'adapter.mjs', 'config.mjs', 'core.mjs',
                     'worker.mjs', 'dashboard.html', 'dashboard.css', 'dashboard.js']:
            assert files['/app/cloud/' + path] == '644'
        assert files['/app/server/publish.mjs'] == '644'
        assert files['/app/validator/json.jar'] == '644'
        assert any(p.startswith('/app/cloud/migrations/') and p.endswith('.sql') for p in files)
        assert any(p.startswith('/app/validator/') and p.endswith('.class') for p in files)
    else:
        assert files['/dootah-server'] == '755'
        assert run('docker', 'run', '--rm', '--network=none', '--entrypoint', 'stat', image,
                   '-c', '%a:%u:%g', '/state').stdout.strip() == '700:10001:10001'

    # Check access as the actual service UID, including traversal and dependencies.
    run('docker', 'run', '--rm', '--network=none', '--user', '10001:10001', '--entrypoint',
        'find', image, *roots, '-exec', 'sh', '-ec',
        'for p do test -r "$p"; if [ -d "$p" ]; then test -x "$p"; fi; test ! -w "$p"; done', 'sh', '{}', '+')
    if cloud:
        run('docker', 'run', '--rm', '--network=none', '--user', '10001:10001', '--entrypoint',
            'sh', image, '-ec', '"$DOOTAH_JAVA" -Xmx128m -cp "$DOOTAH_VALIDATOR_CLASSPATH" Validate --self-test')

    # Test the unchanged secret boundary using a generated, non-production probe.
    with tempfile.TemporaryDirectory(prefix='dootah-image-permissions-') as directory:
        secret = Path(directory) / 'probe'
        secret.write_text('test-only-private-input\n')
        secret.chmod(0o600)
        common = ['docker', 'run', '--network=none', '--read-only', '--tmpfs', '/tmp',
                  '--tmpfs', '/run/private', '-v', f'{secret}:/run/input/probe:ro',
                  '--entrypoint', '/entrypoint.sh']
        if not cloud:
            common += ['--tmpfs', '/state']
        cid = run(*common, '-d', image, 'sh', '-ec',
                  'test "$(id -u)" = 10001; test "$(id -g)" = 10001; '
                  'test "$(stat -c %a /run/private)" = 700; '
                  'test "$(stat -c %a /run/private/probe)" = 600; '
                  'test -r /run/private/probe; '
                  'if [ -d /state ]; then test -w /state; fi; sleep 60').stdout.strip()
        try:
            # exec waits for the entrypoint snapshot without reading its contents.
            run('docker', 'exec', '--user', '10001:10001', cid, 'sh', '-ec',
                'i=0; until test -r /run/private/probe; do i=$((i+1)); test "$i" -lt 50; sleep .1; done')
            assert run('docker', 'exec', cid, 'stat', '-c', '%a:%u:%g',
                       '/run/private/probe').stdout.strip() == '600:10001:10001'
            run('docker', 'exec', '--user', '10002:10002', cid, 'sh', '-ec',
                'test ! -r /run/private/probe; test ! -r /run/input/probe')
            for path in ['/run/private/probe', '/run/input/probe']:
                assert run('docker', 'exec', '--user', '10002:10002', cid, 'sh', '-c',
                           'cat "$1" >/dev/null 2>&1', 'sh', path, check=False).returncode != 0
            assert secret.stat().st_mode & 0o777 == 0o600
        finally:
            run('docker', 'rm', '-f', cid)
        # A separate inode also avoids host-sharing metadata caches from the
        # prior bind mount obscuring this negative test on Docker Desktop.
        insecure = Path(directory) / 'insecure-probe'
        insecure.write_text('test-only-insecure-input\n')
        insecure.chmod(0o644)
        negative = [arg.replace(f'{secret}:/run/input/probe:ro',
                                f'{insecure}:/run/input/probe:ro') for arg in common]
        refused = run(*negative, '--rm', image, 'true', check=False)
        assert refused.returncode != 0 and 'secret_permissions_invalid' in refused.stderr
    return {'image': image, 'payloadEntries': len(files), 'runtimeUid': 10001,
            'immutablePayloadPassed': True, 'secretIsolationPassed': True}


def main():
    checks = [audit('dootah-cloud:9d', True), audit('dootah-xprem:9d', False)]
    receipt = {'passed': True, 'images': checks}
    evidence = os.environ.get('DOOTAH_BUILD_EVIDENCE')
    if evidence:
        Path(evidence).mkdir(parents=True, exist_ok=True)
        (Path(evidence) / 'image-permissions.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('PASS: production image permissions, runtime UID access, validator and secret isolation')


if __name__ == '__main__':
    main()
