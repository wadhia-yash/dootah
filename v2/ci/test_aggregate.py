import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
from gates import MANIFEST
from release import extract


class AggregateEvidence(unittest.TestCase):
    def test_aggregate_rejects_missing_wrong_source_empty_and_skipped_suites(self):
        specs=json.loads(MANIFEST.read_text())['suites']
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);sha='a'*40
            for job in ['sdk','server','security']:
                dest=root/job;dest.mkdir()
                suites=[dict(id=s['id'],passed=s['minimum'],failed=0,skipped=len(s.get('allowedSkips',[]))) for s in specs if s['job']==job]
                (dest/'result.json').write_text(json.dumps(dict(job=job,sourceCommit=sha,passed=True,suites=suites)))
            def run():
                return subprocess.run([sys.executable,str(Path(__file__).with_name('combine.py')),str(root),sha],capture_output=True).returncode
            self.assertEqual(run(),0)
            path=root/'sdk/result.json';good=json.loads(path.read_text())
            for update in [dict(sourceCommit='b'*40),dict(passed=False),dict(suites=[]),
                           dict(suites=[dict(s,skipped=1) for s in good['suites']]),
                           dict(suites=[dict(s,passed=0) for s in good['suites']]),
                           dict(suites=good['suites']+good['suites'][:1])]:
                path.write_text(json.dumps(dict(good,**update)))
                self.assertNotEqual(run(),0)
            path.unlink();self.assertNotEqual(run(),0)

    def test_candidate_zip_rejects_traversal_and_absolute_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for name in ['../key','/tmp/key']:
                archive=root/'bad.zip'
                with zipfile.ZipFile(archive,'w') as z:z.writestr(name,b'not a key')
                with self.assertRaises(ValueError):extract(archive,root/'out')
