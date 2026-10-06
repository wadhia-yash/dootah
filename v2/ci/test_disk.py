"""The SDK gate must reclaim producer-only disk before the isolated consumer without losing evidence."""
from collections import namedtuple
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from run import GIB, CONSUMER_MIN_FREE, failure_receipt, reclaim, reclaimable, require_free, usage

CI = Path(__file__).resolve().parent
Usage = namedtuple('Usage', 'total used free')


class Reclaim(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.work = work = Path(self.temp.name)
        self.checkout = co = work/'source'; co.mkdir(); (work/'tmp').mkdir()
        git = lambda *a: subprocess.run(['git', '-C', co, *a], check=True, capture_output=True)
        git('init', '-q')
        (co/'.gitignore').write_text('build/\n.gradle/\nnode_modules/\n__pycache__/\n')
        for name in ['v2/android-sdk/version.properties', 'v2/native-consumer/gradlew', 'v2/native-consumer/app/build.gradle']:
            (co/name).parent.mkdir(parents=True, exist_ok=True); (co/name).write_text('tracked')
        git('add', '.'); git('-c', 'user.name=t', '-c', 'user.email=t@invalid', 'commit', '-qm', 'fixture')
        # Producer outputs and caches that exist once staging has finished.
        for name in ['v2/native-consumer/app/build/outputs/dootah/debug/hooks.json', 'v2/runtime-spike/node_modules/x/index.js',
                     'v2/android-sdk/build/maven/dev/dootah/runtime-v2/x.aar', 'v2/runtime-spike/android/.gradle/state',
                     'v2/ci/__pycache__/run.pyc']:
            (co/name).parent.mkdir(parents=True, exist_ok=True); (co/name).write_bytes(b'0'*4096)
        for name in ['gradle-home/caches/transforms/x', 'npm-cache/_cacache/x', 'tmp/metro-cache/x']:
            (work/name).parent.mkdir(parents=True, exist_ok=True); (work/name).write_bytes(b'0'*4096)
        # Everything later stages read.
        self.kept = ['source/reports/sdk-runtime/TEST-a.xml', 'source/reports/packaging.xml', 'stage/SHA256SUMS',
                     'stage/inventory.json', 'stage/audit.json', 'stage/repository/dev/dootah/runtime-v2/0.1.0-alpha.1/runtime-v2-0.1.0-alpha.1.aar',
                     'gradle.zip', 'source/v2/android-sdk/version.properties', 'source/v2/native-consumer/gradlew',
                     'source/v2/native-consumer/app/build.gradle']
        for name in self.kept:
            if not (work/name).exists():
                (work/name).parent.mkdir(parents=True, exist_ok=True); (work/name).write_text('kept')

    def tearDown(self):
        self.temp.cleanup()

    def test_required_artifacts_survive_and_producer_state_is_reclaimed(self):
        reclaim(reclaimable(self.checkout, self.work))
        for name in self.kept:
            self.assertTrue((self.work/name).is_file(), name)
        for name in ['source/v2/native-consumer/app/build', 'source/v2/runtime-spike/node_modules', 'source/v2/android-sdk/build/maven',
                     'source/v2/runtime-spike/android/.gradle', 'gradle-home', 'npm-cache', 'tmp/metro-cache']:
            self.assertFalse((self.work/name).exists(), name)
        self.assertTrue((self.work/'tmp').is_dir())  # still the consumer's TMPDIR

    def test_protected_reports_are_never_reclaimed_even_if_ignored(self):
        (self.checkout/'.git/info/exclude').write_text('reports/\n')
        with self.assertRaisesRegex(ValueError, 'Refusing to reclaim protected evidence'):
            reclaimable(self.checkout, self.work)
        self.assertTrue((self.work/'source/reports/packaging.xml').is_file())

    def test_snapshot_reports_fixed_labels_and_sizes_only(self):
        snap = usage('after staging', self.work)
        self.assertEqual(set(snap), {'point', 'freeBytes', 'bytes'})
        self.assertLessEqual(set(snap['bytes']), {'checkout', 'nodeModules', 'producerGradleHome', 'npmCache', 'tmp', 'stage', 'gradleZip'})
        self.assertGreater(snap['bytes']['producerGradleHome'], 0)
        self.assertTrue(all(isinstance(v, int) for v in snap['bytes'].values()))
        self.assertNotIn(str(self.work), repr(snap))


class DiskGuard(unittest.TestCase):
    def test_guard_fails_clearly_and_the_receipt_keeps_disk_state(self):
        with tempfile.TemporaryDirectory() as d:
            work = Path(d)
            with patch('shutil.disk_usage', return_value=Usage(100*GIB, 99*GIB, 94208)):
                with self.assertRaises(ValueError) as caught:
                    require_free(work, CONSUMER_MIN_FREE, 'isolated staged consumer')
                failure = failure_receipt('sdk', 'a'*40, 'building isolated staged consumer Debug/D8 and Release/R8',
                                          caught.exception, work, [usage('reclaiming', work)])
        self.assertRegex(str(caught.exception),
                         r'^Insufficient disk for isolated staged consumer: required >= \d+\.\d GiB, available 0\.0 GiB$')
        self.assertEqual(failure['error']['message'], str(caught.exception))
        self.assertEqual(failure['host']['diskFreeBytes'], 94208)
        self.assertEqual(failure['disk'][0]['freeBytes'], 94208)
        with tempfile.TemporaryDirectory() as d:
            with patch('shutil.disk_usage', return_value=Usage(100*GIB, 0, CONSUMER_MIN_FREE)):
                require_free(Path(d), CONSUMER_MIN_FREE, 'isolated staged consumer')


class GateOrder(unittest.TestCase):
    def test_reclaim_and_guard_run_after_staging_and_before_the_consumer(self):
        text = (CI/'run.py').read_text()
        order = ["'v2/android-sdk/release/stage.sh'", 'reclaim(reclaimable(checkout,work))',
                 'require_free(work,CONSUMER_MIN_FREE', "'v2/android-sdk/release/consumer.py'", 'stage_versions(checkout,stage)',
                 'suites=verify(job,checkout)']
        positions = [text.index(marker) for marker in order]
        self.assertEqual(positions, sorted(positions))
        self.assertEqual(text.count('reclaim(reclaimable('), 1)
        # Reports must be snapshotted and verified before anything is reclaimed.
        self.assertLess(text.index('snapshot_reports(job,checkout)'), text.index('reclaim(reclaimable('))

    def test_consumer_gradle_home_is_fresh_and_never_the_producer_home(self):
        run = (CI/'run.py').read_text()
        consumer = (CI.parent/'android-sdk/release/consumer.py').read_text()
        self.assertIn("GRADLE_USER_HOME=str(work/'gradle-home')", run)
        self.assertIn("work = Path(tempfile.mkdtemp(prefix='dootah-release-consumer-'))", consumer)
        self.assertIn("GRADLE_USER_HOME=str(work / 'gradle-home')", consumer)
        self.assertRegex(consumer, r"exclusiveContent \{\s*forRepository \{ maven \{ url = uri\(%s\) \} \}")
        self.assertNotRegex(consumer, r'mavenLocal|android-sdk/build/maven')
        self.assertIn("work/'gradle-home'", re.search(r'def reclaimable.*?\n\n\n', run, re.S).group(0))


if __name__ == '__main__':
    unittest.main()
