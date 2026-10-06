"""Fail-closed required-suite reader. Reports come only from the current clean run."""
import json
import re
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

MANIFEST = Path(__file__).with_name('required-suites.json')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def tap(text):
    require(not re.search(r'^\s*(not ok|Bail out!|ok \d+.*#\s*(?:SKIP|TODO)\b)', text, re.M | re.I), 'TAP failure/skip')
    values = {}
    for key in ['tests', 'pass', 'fail', 'cancelled', 'skipped', 'todo']:
        matches = re.findall(r'^# ' + key + r' (\d+)$', text, re.M)
        require(len(matches) == 1, 'Missing/duplicate TAP summary: ' + key)
        values[key] = int(matches[0])
    names = re.findall(r'^ok \d+ - (.+)$', text, re.M)
    plan = re.findall(r'^1\.\.(\d+)$', text, re.M)
    require(len(plan) == 1 and int(plan[0]) == len(names) == values['tests'] == values['pass'], 'Incomplete TAP plan')
    require(values['tests'] > 0 and not any(values[k] for k in ['fail', 'cancelled', 'skipped', 'todo']), 'TAP not fully passing')
    require(len(set(names)) == len(names), 'Duplicate TAP tests')
    return names


def suite(spec, root):
    files = sorted(root.glob(spec['report']))
    require(bool(files), 'Missing report: ' + spec['id'])
    passed, skipped = 0, 0
    if spec['format'] == 'junit':
        classes = {}
        for file in files:
            tree = ET.parse(file).getroot()
            cases = list(tree.iter('testcase'))
            require(bool(cases), 'Empty JUnit file')
            for group in tree.iter('testsuite'):
                require(int(group.get('tests', '-1')) == len(list(group.iter('testcase'))), 'Incomplete JUnit suite')
                require(not any(int(group.get(k, '0')) for k in ['failures', 'errors', 'skipped']), 'JUnit failure/skip summary')
            for case in cases:
                require(all(case.find(k) is None for k in ['failure', 'error', 'skipped']), 'JUnit failure/skip')
                key = case.get('classname', '').rsplit('.', 1)[-1]
                classes[key] = classes.get(key, 0) + 1
            passed += len(cases)
        require(all(classes.get(k, 0) >= v for k, v in spec.get('classes', {}).items()), 'Missing/empty required test class')
    elif spec['format'] == 'tap':
        require(len(files) == 1, 'Ambiguous TAP report')
        names = tap(files[0].read_text())
        expected = spec.get('names')
        require(expected is None or set(expected) <= set(names), 'Missing required TAP tests')
        passed = len(expected) if expected else len(names)
    elif spec['format'] == 'go':
        events = [json.loads(line) for line in files[0].read_text().splitlines()]
        require(not any(e.get('Action') == 'fail' for e in events), 'Go failure')
        ended = {}
        started = set()
        for e in events:
            key = (e.get('Package'), e.get('Test'))
            if e['Action'] == 'run':
                started.add(key)
            if e['Action'] in ['pass', 'skip']:
                require(key not in ended, 'Duplicate Go result')
                ended[key] = e['Action']
        require(started and started <= ended.keys(), 'Incomplete Go test stream')
        packages = {k[0] for k in started}
        require(all(ended.get((p, None)) == 'pass' for p in packages), 'Missing Go package completion')
        skips = [k[1] for k, v in ended.items() if v == 'skip']
        require(len(skips) == len(set(skips)) and set(skips) == set(spec['allowedSkips']), 'Unexpected/missing Go exclusions')
        passed = sum(k[1] is not None and v == 'pass' for k, v in ended.items())
        skipped = len(skips)
    elif spec['format'] == 'acceptance':
        receipt = json.loads(files[0].read_text())
        require(receipt.get('passed') is True and receipt.get('restorePassed') is True, 'Restore acceptance missing')
        require(receipt.get('cleanupPassed') is True, 'Compose cleanup missing')
        require(len(receipt.get('checks', [])) >= 15, 'Incomplete Compose acceptance')
        passed = 1
    else:
        raise ValueError('Unknown report format')
    require(passed >= spec['minimum'], 'Missing/empty tests: ' + spec['id'])
    return dict(id=spec['id'], passed=passed, failed=0, skipped=skipped)


def verify(job, root):
    specs = json.loads(MANIFEST.read_text())['suites']
    require(len({s['id'] for s in specs}) == len(specs), 'Duplicate manifest suite')
    selected = [s for s in specs if s['job'] == job]
    require(bool(selected), 'Unknown job')
    return [suite(s, root) for s in selected]


def snapshot_reports(job, root):
    # Staging runs npm ci, which removes test reports inside Expo's node_modules.
    # Preserve this invocation's reports before that reinstall, never restore caches.
    for spec in json.loads(MANIFEST.read_text())['suites']:
        if spec['job'] != job or not spec.get('sourceReport'):
            continue
        files=sorted(root.glob(spec['sourceReport']))
        require(bool(files),'Missing fresh source report: '+spec['id'])
        target=root/Path(spec['report']).parent
        require(not target.exists(),'Refusing stale report snapshot: '+spec['id'])
        target.mkdir(parents=True)
        for file in files: shutil.copy2(file,target/file.name)
