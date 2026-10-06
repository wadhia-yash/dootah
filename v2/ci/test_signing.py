"""Real cryptographic export with a throwaway, passphrase-protected CI identity."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'android-sdk/release'))
import package
import sign


class TestOnlySigning(unittest.TestCase):
    def test_ephemeral_signature_export_and_tamper_rejection(self):
        with tempfile.TemporaryDirectory(prefix='dsg-',dir='/tmp') as tmp:
            work=Path(tmp);gnupg=work/'gnupg';gnupg.mkdir(mode=0o700)
            password=work/'passphrase';password.write_text(secrets.token_hex(32));password.chmod(0o600)
            env=dict(os.environ,GNUPGHOME=str(gnupg))
            def gpg(*args,check=True):
                return subprocess.run(['gpg','--batch',*args],env=env,capture_output=True,check=check)
            try:
                gpg('--pinentry-mode','loopback','--passphrase-file',str(password),'--quick-generate-key',
                    'Dootah CI TEST ONLY <test@example.invalid>','rsa2048','sign','1d')
                records=gpg('--with-colons','--list-secret-keys').stdout.decode().splitlines()
                fingerprint=next(r.split(':')[9] for r in records if r.startswith('fpr:'))
                stage=work/'stage';repo=stage/'repository';repo.mkdir(parents=True)
                (repo/'test.pom').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><developers><developer><id>ci-test</id><name>TEST ONLY</name></developer></developers></project>')
                package.checksums(repo,stage/'SHA256SUMS')
                (stage/'audit.json').write_text('{"findings": []}')
                receipt=dict.fromkeys(['freshGradleHome','exclusiveDootahRepository','debugD8','releaseR8'],True)
                receipt['stageManifestSha256']=hashlib.sha256((stage/'SHA256SUMS').read_bytes()).hexdigest()
                (stage/'consumer-result.json').write_text(json.dumps(receipt))
                with patch.dict(os.environ,dict(env,GPG_KEY_ID=fingerprint,DOOTAH_GPG_PASSPHRASE_FILE=str(password))):
                    # Capture real GPG output as private diagnostics; never upload identity/key material.
                    original=sign.subprocess.run
                    def quiet(args,**kw):
                        return original(args,**dict(kw,stdout=subprocess.PIPE,stderr=subprocess.PIPE))
                    with patch.object(sign.subprocess,'run',side_effect=quiet):
                        sign.export(stage,work/'test-only.zip',work/'ledger.json')
                        password.chmod(0o644)
                        with self.assertRaisesRegex(ValueError,'private'):
                            sign.export(stage,work/'bad.zip',work/'second-ledger.json')
                        self.assertFalse((work/'bad.zip').exists())
                with zipfile.ZipFile(work/'test-only.zip') as archive: archive.extractall(work/'signed')
                signed=work/'signed/test.pom'
                gpg('--verify',str(signed)+'.asc',str(signed))
                signed.write_text('tampered')
                self.assertNotEqual(gpg('--verify',str(signed)+'.asc',str(signed),check=False).returncode,0)
            finally:
                subprocess.run(['gpgconf','--kill','all'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
