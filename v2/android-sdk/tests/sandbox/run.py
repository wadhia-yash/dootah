#!/usr/bin/env python3
"""Run the separate diagnostic APK; never replaces or clears the consumer APK."""
import argparse, base64, json, pathlib, subprocess
p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--output', required=True)
a = p.parse_args()
root = pathlib.Path(__file__).resolve().parent
apk = root / 'build/outputs/apk/debug/Dootah Sandbox Proof-debug.apk'
adb = ['adb', '-s', a.serial]
subprocess.run(adb + ['install', '-r', str(apk)], check=True)
program = 'const input={price:100,discount:20}; String(input.price-input.discount)'
result = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-e', 'program',
    base64.b64encode(program.encode()).decode(), 'dev.dootah.sandboxproof/.SandboxProof'],
    capture_output=True, text=True, timeout=45, check=True)
for line in result.stdout.splitlines():
    if line.startswith('INSTRUMENTATION_RESULT: evidence='):
        evidence = json.loads(line.split('=', 1)[1])
        assert evidence['status'] == 'PASS'
        assert evidence['remoteResult'] == evidence['portable']['result'] == '80'
        assert evidence['portable']['rejections'] == 17
        evidence['globalNames'] = json.loads(evidence['globalNames'])
        pathlib.Path(a.output).write_text(json.dumps(evidence, indent=2) + '\n')
        print(json.dumps({'status': evidence['status'], 'portable': evidence['portable']}))
        break
else:
    raise RuntimeError(result.stdout + result.stderr)
