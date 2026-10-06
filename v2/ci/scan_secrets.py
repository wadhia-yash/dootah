"""Scan all tracked content with Gitleaks. Exact-byte, reviewed false positives only."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from gates import require


def allowed(finding, root, exceptions):
    name=finding['File'].removeprefix('/src/')
    file=root/name
    return any(e['path']==name and e['rule']==finding['RuleID'] and e['line']==finding['StartLine']
               and e['fileSha256']==hashlib.sha256(file.read_bytes()).hexdigest() for e in exceptions)


def scan(root):
    exceptions=json.loads((root/'v2/ci/secret-exceptions.json').read_text())
    with tempfile.TemporaryDirectory(prefix='dootah-secret-scan-') as tmp:
        output=Path(tmp)
        result=subprocess.run(['docker','run','--rm','-v',str(root)+':/src:ro','-v',str(output)+':/report',
                               'dootah-ci-tools:9e','gitleaks','dir','/src','--redact=100','--no-banner',
                               '--report-format=json','--report-path=/report/findings.json'],capture_output=True)
        require(result.returncode in [0,1] and (output/'findings.json').exists(),'Secret scanner did not complete')
        findings=json.loads((output/'findings.json').read_text())
        unexpected=[x for x in findings if not allowed(x,root,exceptions)]
        for item in unexpected:
            print('Secret scan finding: '+item['File']+':'+str(item['StartLine'])+' ('+item['RuleID']+')',file=sys.stderr)
        require(not unexpected,'Unexpected secret scan finding; contents suppressed')
        print('Tracked-source secret scan passed; '+str(len(findings))+' exact-byte reviewed false positives.')


if __name__=='__main__': scan(Path(sys.argv[1]).resolve())
