import json
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from gates import suite, tap, verify, snapshot_reports
from versions import source
from scan_secrets import allowed


class RequiredSuites(unittest.TestCase):
    def test_tap_requires_complete_nonempty_summary(self):
        good='TAP version 13\nok 1 - example\n1..1\n# tests 1\n# pass 1\n# fail 0\n# cancelled 0\n# skipped 0\n# todo 0\n'
        self.assertEqual(tap(good),['example'])
        for bad in ['',good.replace('# skipped 0\n',''),good.replace('1..1','1..2'),
                    good.replace('ok 1','not ok 1'),good.replace('example','example # SKIP missing fixture'),
                    good.replace('# pass 1','# pass 0'),good.replace('# todo 0','# todo 1'),
                    good+'# skipped 0\n',good.replace('ok 1 - example\n','')]:
            with self.assertRaises(ValueError): tap(bad)

    def test_missing_required_suite(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError): verify('sdk',Path(d))

    def test_junit_missing_empty_skipped_failed_and_missing_class(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); report=root/'x.xml'
            spec=dict(id='test',format='junit',report='*.xml',minimum=1,classes={'Expected':1})
            valid='<testsuite tests="1"><testcase classname="Expected" name="x"/></testsuite>'
            for bad in ['<testsuite tests="0"/>',valid.replace('/>','><skipped/></testcase>'),
                        valid.replace('/>','><failure/></testcase>'),valid.replace('Expected','Other'),
                        valid.replace('tests="1"','tests="2"')]:
                report.write_text(bad)
                with self.assertRaises(ValueError): suite(spec,root)
            report.write_text(valid)
            self.assertEqual(suite(spec,root)['passed'],1)

    def test_go_rejects_extra_skip_failure_and_incomplete_test(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);spec=dict(id='go',format='go',report='go.json',minimum=1,allowedSkips=['Azure'])
            events=[dict(Package='p',Test='Test',Action='run'),dict(Package='p',Test='Test',Action='pass'),
                    dict(Package='p',Test='Azure',Action='run'),dict(Package='p',Test='Azure',Action='skip'),
                    dict(Package='p',Action='pass')]
            def write(items): (root/'go.json').write_text('\n'.join(map(json.dumps,items)))
            write(events);self.assertEqual(suite(spec,root)['skipped'],1)
            for extra in [dict(Package='p',Test='Other',Action='skip'),dict(Package='p',Test='Other',Action='fail'),
                          dict(Package='p',Test='Other',Action='run')]:
                write(events+[extra])
                with self.assertRaises(ValueError):suite(spec,root)

    def test_restore_and_cleanup_are_required(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);spec=dict(id='restore',format='acceptance',report='acceptance.json',minimum=1)
            data=dict(passed=True,restorePassed=True,cleanupPassed=True,checks=['check']*15)
            (root/'acceptance.json').write_text(json.dumps(data));suite(spec,root)
            for field in ['passed','restorePassed','cleanupPassed']:
                (root/'acceptance.json').write_text(json.dumps(dict(data,**{field:False})))
                with self.assertRaises(ValueError):suite(spec,root)

    def test_cloud_cannot_disappear_behind_other_passing_tests(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'x.tap').write_text('ok 1 - unrelated\n1..1\n# tests 1\n# pass 1\n# fail 0\n# cancelled 0\n# skipped 0\n# todo 0\n')
            with self.assertRaises(ValueError):suite(dict(id='cloud',format='tap',report='x.tap',minimum=1,names=['database']),root)

    def test_frozen_source_and_dependency_pins(self):
        self.assertEqual(source(Path(__file__).resolve().parents[2])['runtimeAbi'],2)

    def test_secret_exception_cannot_hide_changed_or_new_secret(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);file=root/'fixture';file.write_text('reviewed public content')
            finding=dict(File='/src/fixture',RuleID='rule',StartLine=1)
            exceptions=[dict(path='fixture',rule='rule',line=1,fileSha256=hashlib.sha256(file.read_bytes()).hexdigest())]
            self.assertTrue(allowed(finding,root,exceptions))
            self.assertFalse(allowed(dict(finding,StartLine=2),root,exceptions))
            file.write_text('changed content')
            self.assertFalse(allowed(finding,root,exceptions))

    def test_native_reports_survive_reinstall_without_accepting_stale_snapshots(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);original=root/'node_modules';original.mkdir()
            report=original/'TEST-health.xml'
            report.write_text('<testsuite tests="1"><testcase classname="Health" name="x"/></testsuite>')
            spec=dict(id='health',job='sdk',format='junit',sourceReport='node_modules/*.xml',report='reports/health/*.xml',minimum=1)
            manifest=root/'manifest.json';manifest.write_text(json.dumps(dict(suites=[spec])))
            with patch('gates.MANIFEST',manifest):
                snapshot_reports('sdk',root)
                with self.assertRaises(ValueError):snapshot_reports('sdk',root)
                report.unlink()
                self.assertEqual(verify('sdk',root)[0]['passed'],1)
