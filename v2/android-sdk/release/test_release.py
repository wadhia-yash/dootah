import json
import hashlib
import os
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch
import zipfile
import io

import package
import consumer
import sign


class ReleaseGates(unittest.TestCase):
    def test_gradle_metadata_format_version_is_first(self):
        payload = package.module_bytes({'component': {}, 'formatVersion': '1.1', 'variants': []})
        self.assertEqual(next(iter(json.loads(payload))), 'formatVersion')

    def test_certificate_allowance_is_not_a_general_pem_exception(self):
        findings = []
        package.audit_bytes('x.aar!/assets/expo-root.pem', b'not the upstream certificate', findings)
        self.assertEqual(len(findings), 1)

    def test_optional_gradle_zip_requires_pinned_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'gradle.zip'; archive.write_bytes(b'fixture distribution bytes')
            props = root / 'wrapper.properties'
            props.write_text('distributionUrl=https://example.invalid/gradle.zip\n')
            with self.assertRaises(ValueError):
                consumer.configure_distribution(props, archive)
            props.write_text(props.read_text() + 'distributionSha256Sum=' + hashlib.sha256(archive.read_bytes()).hexdigest() + '\n')
            self.assertEqual(consumer.configure_distribution(props, archive), 'checksum-verified upstream ZIP')
            self.assertIn(archive.resolve().as_uri(), props.read_text())
            archive.write_bytes(b'changed')
            with self.assertRaises(ValueError):
                consumer.configure_distribution(props, archive)

    def test_nested_secret_and_path_detection(self):
        for unsafe in [b'/Users/example/private/file', b'/tmp/test/file',
                       b'-----BEGIN PRIVATE KEY-----', b'ghp_' + b'a' * 36]:
            findings = []
            nested = package.zip_bytes({'classes.jar': package.zip_bytes({'payload': unsafe})})
            package.audit_bytes('runtime.aar', nested, findings)
            self.assertEqual(findings, ['runtime.aar!/classes.jar!/payload'])

    def test_archive_path_traversal_rejected(self):
        for name in ['../key', '/private/key', 'some/../../key']:
            with self.assertRaises(ValueError):
                package.zip_bytes({name: b'value'})

    def test_duplicate_archive_entries_rejected(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            archive.writestr('x', b'first')
            with self.assertWarns(UserWarning):
                archive.writestr('x', b'second')
        with self.assertRaises(ValueError):
            package.unzip(stream.getvalue())

    def test_notice_preservation_and_deterministic_archive(self):
        entries = {'META-INF/license': b'original copyright', 'classes': b'compiled'}
        first = package.zip_bytes(entries)
        self.assertEqual(first, package.zip_bytes(dict(reversed(list(entries.items())))))
        self.assertEqual(package.unzip(first), entries)

    def test_manifest_rejects_mutated_added_and_removed_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory)
            repo = stage / 'repository'; repo.mkdir()
            artifact = repo / 'artifact.jar'; artifact.write_bytes(b'original')
            package.checksums(repo, stage / 'SHA256SUMS')
            sign.verify_manifest(stage)
            artifact.write_bytes(b'changed')
            with self.assertRaises(ValueError):
                sign.verify_manifest(stage)
            artifact.write_bytes(b'original')
            (repo / 'extra.jar').write_bytes(b'other')
            with self.assertRaises(ValueError):
                sign.verify_manifest(stage)
            (repo / 'extra.jar').unlink(); artifact.unlink()
            with self.assertRaises(ValueError):
                sign.verify_manifest(stage)

    def fixture(self, stage):
        repo = stage / 'repository'; repo.mkdir()
        (repo / 'a.pom').write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
            <developers><developer><id>test</id><name>Packaging test fixture</name></developer></developers>
            </project>''')
        package.checksums(repo, stage / 'SHA256SUMS')
        (stage / 'audit.json').write_text('{"findings": []}')
        (stage / 'consumer-result.json').write_text(json.dumps(dict(
            dict.fromkeys(['freshGradleHome', 'exclusiveDootahRepository', 'debugD8', 'releaseR8'], True),
            stageManifestSha256=hashlib.sha256((stage / 'SHA256SUMS').read_bytes()).hexdigest())))

    def test_signing_required_and_no_partial_output(self):
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory); self.fixture(stage)
            with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, 'GPG_KEY_ID'):
                sign.export(stage, stage / 'out.zip', stage / 'ledger.json')
            self.assertFalse((stage / 'out.zip').exists())
            self.assertFalse((stage / 'ledger.json').exists())

    def test_immutable_coordinate_rejected_before_signing(self):
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory); self.fixture(stage)
            ledger = stage / 'ledger.json'
            ledger.write_text('{"a.pom": "old-content"}')
            with self.assertRaisesRegex(ValueError, 'Immutable coordinate'):
                sign.export(stage, stage / 'out.zip', ledger)
            self.assertEqual(json.loads(ledger.read_text()), {'a.pom': 'old-content'})

    def test_signing_orchestration_verifies_every_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory); self.fixture(stage)
            calls = []
            def gpg(arguments, **options):
                calls.append(arguments)
                if '--detach-sign' in arguments:
                    Path(arguments[-1] + '.asc').write_text('TEST SIGNATURE; NOT CRYPTOGRAPHIC')
            with patch.dict(os.environ, {'GPG_KEY_ID': 'test-key'}), patch.object(sign.subprocess, 'run', side_effect=gpg):
                sign.export(stage, stage / 'out.zip', stage / 'ledger.json')
            self.assertEqual(len(calls), 2)
            self.assertIn('--verify', calls[1])
            with zipfile.ZipFile(stage / 'out.zip') as archive:
                self.assertIn('a.pom.asc', archive.namelist())
                self.assertIn('a.pom.sha256', archive.namelist())
            self.assertIn('a.pom', json.loads((stage / 'ledger.json').read_text()))

    def test_signature_failure_leaves_no_bundle_or_ledger(self):
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory); self.fixture(stage)
            with patch.dict(os.environ, {'GPG_KEY_ID': 'test-key'}), patch.object(sign.subprocess, 'run',
                    side_effect=subprocess.CalledProcessError(2, 'gpg')), self.assertRaises(subprocess.CalledProcessError):
                sign.export(stage, stage / 'out.zip', stage / 'ledger.json')
            self.assertFalse((stage / 'out.zip').exists())
            self.assertFalse((stage / 'ledger.json').exists())

    def test_missing_consumer_or_developer_metadata_blocks_export(self):
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory); self.fixture(stage)
            (stage / 'consumer-result.json').write_text('{}')
            with self.assertRaisesRegex(ValueError, 'consumer'):
                sign.export(stage, stage / 'out.zip', stage / 'ledger.json')
            (stage / 'consumer-result.json').write_text(json.dumps(dict(
                dict.fromkeys(['freshGradleHome', 'exclusiveDootahRepository', 'debugD8', 'releaseR8'], True),
                stageManifestSha256=hashlib.sha256((stage / 'SHA256SUMS').read_bytes()).hexdigest())))
            (stage / 'repository/a.pom').write_text('<project/>')
            package.checksums(stage / 'repository', stage / 'SHA256SUMS')
            receipt = json.loads((stage / 'consumer-result.json').read_text())
            receipt['stageManifestSha256'] = hashlib.sha256((stage / 'SHA256SUMS').read_bytes()).hexdigest()
            (stage / 'consumer-result.json').write_text(json.dumps(receipt))
            with self.assertRaisesRegex(ValueError, 'DEVELOPER'):
                sign.export(stage, stage / 'out.zip', stage / 'ledger.json')

if __name__ == '__main__':
    unittest.main()
