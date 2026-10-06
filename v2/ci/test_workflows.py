"""Authoritative V2 workflows must bootstrap Android without removed SDK packages."""
from pathlib import Path
import re
import shlex
import unittest

WORKFLOWS = Path(__file__).resolve().parents[2]/'.github/workflows'
V2 = ['v2-ci.yml', 'v2-gates.yml', 'v2-release.yml']
# Retired standalone package: sdkmanager reports "Failed to find package 'tools'" and exits 1.
REMOVED = {'tools'}


def steps(text):
    """Split every `steps:` list into raw step blocks (no YAML dependency on hosted Python)."""
    lines, found = text.splitlines(), []
    for n, line in enumerate(lines):
        key = re.match(r'( *)steps:\s*$', line)
        if not key: continue
        item = None
        for nxt in lines[n+1:]:
            if not nxt.strip() or nxt.lstrip().startswith('#'): continue
            indent = len(nxt)-len(nxt.lstrip(' '))
            if item is None: item = indent
            if indent < item or (indent == item and not nxt.lstrip().startswith('- ')): break
            if indent == item: found.append([])
            found[-1].append(nxt)
    return ['\n'.join(step) for step in found]


def problems(text):
    """Return removed-package requests, unpinned setup-android, or reliance on its package default."""
    errors = []
    for step in steps(text):
        uses = re.search(r'uses:\s*android-actions/setup-android@(\S+)', step)
        if uses:
            if not re.fullmatch(r'[0-9a-f]{40}', uses.group(1)): errors.append('setup-android is not pinned to a full SHA')
            value = re.search(r'^\s*packages:[ \t]*(.*)$', step, re.M)
            if not value: errors.append('setup-android relies on its default packages')
            elif value.group(1).strip()[:1] in ['|', '>']: errors.append('setup-android packages must be one line')
            else:
                # Mirrors the action: split on spaces and install each exact token.
                tokens = value.group(1).split('#')[0].strip().strip('\'"').split(' ')
                errors += ['setup-android requests removed package '+t for t in tokens if t.strip() in REMOVED]
        for match in re.finditer(r'\bsdkmanager\b(.*)', step):
            errors += ['sdkmanager requests removed package '+t for t in shlex.split(match.group(1), comments=True) if t in REMOVED]
    return errors


class AndroidSdkBootstrap(unittest.TestCase):
    def test_v2_workflows_never_request_removed_packages(self):
        for name in V2:
            self.assertEqual(problems((WORKFLOWS/name).read_text()), [], name)
        gates = (WORKFLOWS/'v2-gates.yml').read_text()
        self.assertEqual(sum('setup-android@' in step for step in steps(gates)), 1)

    def test_sdk_gate_installs_every_required_package_explicitly(self):
        # Never depend on what a runner image happens to preinstall; build-tools 35.0.0 is AGP's consumer default.
        requested = set()
        for match in re.finditer(r'\bsdkmanager\b(.*)', (WORKFLOWS/'v2-gates.yml').read_text()):
            requested.update(shlex.split(match.group(1), comments=True))
        self.assertLessEqual({'platforms;android-36', 'build-tools;36.0.0', 'build-tools;35.0.0',
                              'ndk;27.1.12297006', 'cmake;3.22.1'}, requested)

    def test_exact_tokens_and_explicit_packages(self):
        step = ('jobs:\n  sdk:\n    steps:\n      - uses: android-actions/setup-android@'+'a'*40+'\n'
                '        if: matrix.gate == \'sdk\'\n{}      - name: next\n        run: {}\n')
        clean = step.format('        with:\n          packages: platform-tools build-tools;36.0.0 cmdline-tools;latest\n',
                            "sdkmanager 'platform-tools' 'build-tools;36.0.0' 'cmdline-tools;latest'")
        self.assertEqual(problems(clean), [])
        for bad in [step.format('', 'echo'), step.format('        with:\n          packages: tools platform-tools\n', 'echo'),
                    step.format("        with:\n          packages: 'platform-tools tools'\n", 'echo'),
                    step.format('        with:\n          packages: platform-tools\n', "sdkmanager 'tools'"),
                    clean.replace('a'*40, 'v3')]:
            self.assertTrue(problems(bad), bad)

    def test_evidence_upload_only_follows_a_started_gate(self):
        upload = [s for s in steps((WORKFLOWS/'v2-gates.yml').read_text()) if 'evidence/${{ matrix.gate }}' in s]
        self.assertEqual(len(upload), 1)
        self.assertIn("if: always() && steps.gate.outcome != 'skipped'", upload[0])
        self.assertIn('if-no-files-found: error', upload[0])
        self.assertTrue(any('id: gate' in s and 'v2/ci/run.py' in s for s in steps((WORKFLOWS/'v2-gates.yml').read_text())))


if __name__ == '__main__':
    unittest.main()
