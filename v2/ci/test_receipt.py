"""A hosted failure receipt must name the stage and cause without exporting private output."""
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from run import CommandFailed, category, excerpt, failure_receipt as receipt, run


class FailureReceipt(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.work = Path(self.temp.name)/'dootah-9e-x'; self.work.mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def gradle_failure(self):
        work, home = str(self.work), str(Path.home())
        return '\n'.join([f'Isolated consumer: {work}/tmp/dootah-release-consumer-1', '> Task :app:preBuild UP-TO-DATE',
            '> Task :app:minifyReleaseWithR8 FAILED', '', 'FAILURE: Build failed with an exception.', '',
            '* What went wrong:', "Execution failed for task ':app:minifyReleaseWithR8'.",
            f'> Missing class in {work}/source/app/build/r8.txt referenced from {home}/.m2/x.jar',
            'signing.password=hunter2', 'Authorization: Bearer abc', '-----BEGIN PRIVATE KEY-----',
            'sha256-'+'a'*64, '', '* Try:', '> Run with --stacktrace.', 'BUILD FAILED in 9s',
            f"subprocess.CalledProcessError: Command '['{work}/consumer/gradlew']' returned non-zero exit status 1."])

    def test_command_failure_names_stage_command_and_sanitized_cause(self):
        error = CommandFailed(category(['python3', 'v2/android-sdk/release/consumer.py', self.work/'stage']), 1,
                              excerpt(self.gradle_failure(), self.work))
        failure = receipt('sdk', 'a'*40, 'building isolated staged consumer Debug/D8 and Release/R8', error, self.work)
        self.assertEqual(failure['stage'], 'building isolated staged consumer Debug/D8 and Release/R8')
        self.assertEqual(failure['error'], dict(type='CommandFailed', message='Command failed: python3 v2/android-sdk/release/consumer.py (exit 1)'))
        self.assertEqual(failure['command'], dict(category='python3 v2/android-sdk/release/consumer.py', exitCode=1))
        text = '\n'.join(failure['diagnostics'])
        for kept in ['> Task :app:minifyReleaseWithR8 FAILED', "Execution failed for task ':app:minifyReleaseWithR8'.",
                     '<workspace>/source/app/build/r8.txt', '<home>/.m2/x.jar', '<redacted>', 'CalledProcessError']:
            self.assertIn(kept, text)
        for hidden in [str(self.work), str(Path.home())+'/', 'hunter2', 'Bearer', 'PRIVATE KEY', 'a'*32, '* Try:']:
            self.assertNotIn(hidden, text)

    def test_validation_failure_keeps_its_authored_message(self):
        failure = receipt('sdk', 'a'*40, 'staging', ValueError('Stale POM version'), self.work)
        self.assertEqual(failure['error'], dict(type='ValueError', message='Stale POM version'))
        self.assertNotIn('command', failure)
        missing = receipt('sdk', 'a'*40, 'staging', FileNotFoundError(2, 'No such file', str(self.work/'hooks.json')), self.work)
        self.assertIn('<workspace>/hooks.json', missing['error']['message'])

    def test_real_gate_failure_path_writes_sanitized_receipt(self):
        # Drives run() itself: a helper/name error in the except path must never lose failure.json.
        repo = self.work/'repo'; repo.mkdir()
        git = lambda *a: subprocess.run(['git', '-C', repo, *a], check=True, capture_output=True)
        git('init', '-q'); (repo/'README').write_text('fixture\n'); git('add', 'README')
        git('-c', 'user.name=t', '-c', 'user.email=t@invalid', 'commit', '-qm', 'fixture')
        output = self.work/'evidence'
        with patch('run.ROOT', repo), patch('sys.stdout', io.StringIO()), patch('sys.stderr', io.StringIO()) as err:
            with self.assertRaises(FileNotFoundError): run('security', output)
        failure = json.loads((output/'failure.json').read_text())
        self.assertEqual(failure['stage'], 'creating isolated checkout and empty caches')
        self.assertEqual(failure['error']['type'], 'FileNotFoundError')
        self.assertIn('<workspace>/source/v2/android-sdk/version.properties', failure['error']['message'])
        self.assertNotIn('dootah-9e-', json.dumps(failure))
        self.assertEqual([d['point'] for d in failure['disk']], ['creating isolated checkout and empty caches'])
        self.assertGreater(failure['disk'][0]['freeBytes'], 0)
        self.assertIn('FAILED during "creating isolated checkout and empty caches"', err.getvalue())

    def test_receipt_records_host_resources(self):
        host = receipt('sdk', 'a'*40, 'staging', ValueError('x'), self.work)['host']
        self.assertGreater(host['diskFreeBytes'], 0)
        if Path('/proc/meminfo').is_file():
            self.assertGreater(host['memTotalBytes'], 0)

    def test_server_and_security_receipts_never_export_command_output(self):
        error = CommandFailed('python3 v2/deploy/acceptance.py', 1, excerpt('generated value leaked\nRuntimeError: x', self.work))
        for job in ['server', 'security']:
            failure = receipt(job, 'a'*40, 'running', error, self.work)
            self.assertEqual(failure['command']['exitCode'], 1)
            self.assertNotIn('diagnostics', failure)

    def test_command_category_excludes_paths_urls_and_options(self):
        self.assertEqual(category(['curl', '--fail', 'https://example.invalid/x.zip']), 'curl')
        self.assertEqual(category([Path('/private/work/gradlew'), '-p', 'x']), 'gradlew')
        self.assertEqual(category(['git', '-C', '/private/x', 'fetch']), 'git')
        self.assertEqual(category(['v2/native-consumer/gradlew', '-p', 'v2/publishing']), 'v2/native-consumer/gradlew')


if __name__ == '__main__':
    unittest.main()
