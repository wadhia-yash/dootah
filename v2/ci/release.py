#!/usr/bin/env python3
"""Manual signed candidate only. No registry, tag, release or network write operations."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile
from gates import require
from versions import source, stage_versions

ROOT=Path(__file__).resolve().parents[2]


def intent(check_tag=True):
    expected=source(ROOT)
    sha=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    require(not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT).strip(),'Dirty source')
    require(re.fullmatch(r'[0-9a-f]{40}',os.environ.get('RELEASE_COMMIT','')) and sha==os.environ['RELEASE_COMMIT'],'Source intent mismatch')
    require(os.environ.get('RELEASE_VERSION')==expected['publicVersion'],'Public version intent mismatch')
    require(os.environ.get('RELEASE_TAG')=='v'+expected['publicVersion'],'Tag intent mismatch')
    require(os.environ.get('GITHUB_REF') in ['refs/heads/main','refs/heads/pivot/dootah-v2'],'Use a reviewed release branch')
    if check_tag:
        result=subprocess.run(['git','show-ref','--verify','--quiet','refs/tags/'+os.environ['RELEASE_TAG']],cwd=ROOT)
        require(result.returncode==1,'Tag already exists or cannot be checked')
    return sha


def extract(archive,directory):
    with zipfile.ZipFile(archive) as z:
        names=z.namelist()
        require(len(names)==len(set(names)),'Duplicate candidate entry')
        require(all(not Path(n).is_absolute() and '..' not in Path(n).parts for n in names),'Unsafe candidate entry')
        z.extractall(directory)


def sign_candidate(unsigned,output):
    sha=intent(check_tag=False)
    require(not output.exists(),'Output exists')
    fingerprint=os.environ.get('DOOTAH_SIGNING_FINGERPRINT','')
    require(re.fullmatch(r'[A-F0-9]{40}',fingerprint),'Full public signing fingerprint required')
    require(os.environ.get('DOOTAH_SIGNING_KEY_BASE64') and os.environ.get('DOOTAH_SIGNING_PASSPHRASE'),'Signing configuration required')
    ledger=json.loads(os.environ['DOOTAH_COORDINATE_LEDGER'])
    require(isinstance(ledger,dict) and all(isinstance(k,str) and re.fullmatch(r'[a-f0-9]{64}',v) for k,v in ledger.items()),'Invalid immutable coordinate ledger')
    require((unsigned/'candidate.zip.sha256').read_text().strip()==hashlib.sha256((unsigned/'candidate.zip').read_bytes()).hexdigest()+'  candidate.zip','Candidate checksum mismatch')
    with tempfile.TemporaryDirectory(prefix='drc-',dir='/tmp') as tmp:
        work=Path(tmp);work.chmod(0o700)
        stage=work/'stage';extract(unsigned/'candidate.zip',stage)
        metadata=json.loads((stage/'candidate.json').read_text())
        require(metadata['sourceCommit']==sha and metadata['signed'] is False,'Candidate source mismatch')
        versions=stage_versions(ROOT,stage)
        require(all(metadata.get(k)==v for k,v in versions.items()),'Candidate version metadata mismatch')
        key=work/'key';key.write_bytes(base64.b64decode(os.environ['DOOTAH_SIGNING_KEY_BASE64'],validate=True));key.chmod(0o600)
        password=work/'passphrase';password.write_text(os.environ['DOOTAH_SIGNING_PASSPHRASE']);password.chmod(0o600)
        gnupg=work/'gnupg';gnupg.mkdir(mode=0o700)
        ledgerfile=work/'ledger.json';ledgerfile.write_text(json.dumps(ledger))
        env={k:v for k,v in os.environ.items() if not k.startswith('DOOTAH_SIGNING_')}
        env.update(GNUPGHOME=str(gnupg),GPG_KEY_ID=fingerprint,DOOTAH_GPG_PASSPHRASE_FILE=str(password))
        def gpg(*args):
            return subprocess.run(['gpg','--batch',*args],env=env,capture_output=True,check=True).stdout
        try:
            gpg('--import',str(key));key.unlink()
            listed=gpg('--with-colons','--list-secret-keys',fingerprint).decode()
            fingerprints=[line.split(':')[9] for line in listed.splitlines() if line.startswith('fpr:')]
            require(fingerprint in fingerprints,'Configured signing identity unavailable')
            # Existing release code rechecks checksums, consumer binding, developer metadata and ledger.
            subprocess.run(['python3',str(ROOT/'v2/android-sdk/release/sign.py'),str(stage),str(work/'signed-repository.zip'),
                            '--ledger',str(ledgerfile)],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True)
            signed=work/'signed';extract(work/'signed-repository.zip',signed)
            signatures=list(signed.rglob('*.asc'));require(bool(signatures),'No signatures')
            for signature in signatures:
                gpg('--verify',str(signature),str(signature)[:-4])
            output.mkdir(parents=True)
            (output/'signed-repository.zip').write_bytes((work/'signed-repository.zip').read_bytes())
            (output/'coordinate-ledger.json').write_bytes(ledgerfile.read_bytes())
            checksums=[]
            for p in sorted(signed.rglob('*')):
                if p.is_file(): checksums.append(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.relative_to(signed).as_posix())
            (output/'SHA256SUMS').write_text('\n'.join(checksums)+'\n')
            (output/'signed-repository.zip.sha256').write_text(hashlib.sha256((output/'signed-repository.zip').read_bytes()).hexdigest()+'  signed-repository.zip\n')
            (output/'release.json').write_text(json.dumps(dict(versions,sourceCommit=sha,tagIntent=os.environ['RELEASE_TAG'],
                signed=True,signingFingerprint=fingerprint,verifiedSignatures=len(signatures),externalPublication=False),indent=2)+'\n')
        finally:
            subprocess.run(['gpgconf','--kill','all'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)


if __name__=='__main__':
    try:
        if sys.argv[1]=='intent': intent()
        elif sys.argv[1]=='sign': sign_candidate(Path(sys.argv[2]),Path(sys.argv[3]))
        else: raise ValueError('Unknown command')
        print('Release candidate gate passed; no publication performed.')
    except Exception:
        print('Release candidate refused; check source/version, ledger and private signing configuration. Details suppressed.',file=sys.stderr)
        sys.exit(1)
