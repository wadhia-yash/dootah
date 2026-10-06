#!/usr/bin/env python3
"""Small offline public-doc check. No credentials, Cloud requests or publication."""
import argparse
import json
import re
import shlex
import subprocess
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
INVENTORY = ROOT / 'docs/development/public-docs.json'
# Any personal home directory, for any user name; never a hardcoded identity.
HOME = re.compile(r'/Users/(?!<)[^/\s]|/home/(?!<)[A-Za-z0-9_.-]+/|[A-Za-z]:\\Users\\|/var/folders/|~/(?:Documents|Desktop|Downloads)/')
MACHINE = re.compile(HOME.pattern + r'|/Users/|/tmp/dootah|/private/(?:tmp|var)/')
LINK = re.compile(r'(?<!!)\[[^\]\n]+\]\(([^\s)]+)(?:\s+"[^"]*")?\)')
BAD_CLAIMS = re.compile(r'100% OTA|zero runtime overhead|DootahActivity (?:is )?required|id\s*[\x27\x22]dev\.dootah\.compiler')
SECRET = re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?:password|token|secret)\s*[:=]\s*[\x22\x27][A-Za-z0-9+/=_-]{24,}[\x22\x27]', re.I)


def anchors(text):
    found, counts = set(), {}
    # GitHub-style ATX heading slugs, including duplicate headings.
    for line in re.sub(r'```.*?```', '', text, flags=re.S).splitlines():
        m = re.match(r'^#{1,6}\s+(.+?)\s*#*$', line)
        if m:
            slug = re.sub(r'[^\w\- ]', '', m[1].lower()).replace(' ', '-')
            n = counts.get(slug, 0)
            counts[slug] = n + 1
            found.add(slug + (f'-{n}' if n else ''))
    found.update(re.findall(r'<a\s+(?:id|name)=[\x27\x22]([^\x27\x22]+)', text))
    return found


def link_errors(path, text, root=ROOT):
    errors = []
    for match in LINK.finditer(text):
        value = unquote(match[1].strip('<>'))
        parsed = urlsplit(value)
        if parsed.scheme or value.startswith('//'):
            continue
        target = (path.parent / parsed.path).resolve() if parsed.path else path
        if not target.is_relative_to(root.resolve()) or not target.exists():
            errors.append(f'{path.relative_to(root)}: broken link {value}')
        elif parsed.fragment and target.suffix == '.md' and parsed.fragment not in anchors(target.read_text()):
            errors.append(f'{path.relative_to(root)}: missing heading {value}')
    return errors


def cli_surface():
    help_text = subprocess.check_output(['node', str(ROOT / 'v2/publishing/cli.mjs'), '--help'], text=True)
    commands, pairs = set(), set()
    for line in help_text.splitlines():
        if not line.startswith('  '):
            continue
        first = line.strip().split()[0]
        commands.update(first.split('|'))
        pairs.update(re.findall(r'\b(org|app|env|token|release|contract|operation|enrollment) (list|create|revoke|import|register|inspect|ticket)\b', line))
    commands.update(a for a, _ in pairs)
    source = (ROOT / 'v2/publishing/customer.mjs').read_text()
    names = re.search(r'const names = \[(.*?)\];', source, re.S).group(1)
    options = set(re.findall(r"'([^']+)'", names)) | {'allow-local-http', 'help'}
    options.update(re.findall(r'--([a-z][a-z-]+)', help_text))
    return commands, pairs, options


def cli_errors(name, text, surface):
    commands, pairs, options = surface
    errors = []
    for block in re.findall(r'```(?:sh|bash)\n(.*?)```', text, re.S):
        for line in block.replace('\\\n', ' ').splitlines():
            if not re.match(r'^\s*dootah\s', line):
                continue
            words = shlex.split(line, comments=True)
            verb = words[1]
            if verb == '--help':
                continue
            if verb not in commands:
                errors.append(f'{name}: undocumented CLI command {verb}')
            if verb in {a for a, _ in pairs} and (verb, words[2] if len(words) > 2 else '') not in pairs:
                errors.append(f'{name}: unknown CLI subcommand {words[:3]}')
            for word in words[2:]:
                if word.startswith('--') and word[2:].split('=')[0] not in options:
                    errors.append(f'{name}: unknown CLI option {word}')
    return errors


def prose_errors(name, text, versions):
    errors = []
    for pattern, label in [(MACHINE, 'machine-specific path'), (BAD_CLAIMS, 'obsolete/unsupported claim'), (SECRET, 'possible secret')]:
        if pattern.search(text):
            errors.append(f'{name}: {label}')
    for value in re.findall(r'\b\d+\.\d+\.\d+-(?:alpha\.\d+|local)\b', text):
        if value not in versions:
            errors.append(f'{name}: stale SDK version {value}')
    for key, expected in [('Runtime ABI', 2), ('Logic ABI', 1)]:
        for value in re.findall(key + r'\s*[*`]*\s*(\d+)', text, re.I):
            if int(value) != expected:
                errors.append(f'{name}: stale {key} {value}')
    return errors


def check(root=ROOT):
    inventory = json.loads((root / 'docs/development/public-docs.json').read_text())
    files = inventory['public']
    errors = []
    props = dict(line.split('=', 1) for line in (root / 'v2/android-sdk/version.properties').read_text().splitlines()
                 if line and not line.startswith('#'))
    versions = {props['dootah.sdk.version'], props['dootah.public.version']}
    contract = json.loads((root / 'v2/android-sdk/runtime/src/main/assets/dootah-capabilities.json').read_text())
    if (contract['runtimeAbi'], contract['logicAbi']) != (2, 1):
        errors.append('Installed ABI changed: review public documentation explicitly')
    tracked = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '*.md'], cwd=root, text=True).splitlines()
    for name in set(tracked):
        if name not in files and name not in inventory['historical_files'] and not any(name.startswith(p) for p in inventory['historical_roots']):
            errors.append(f'Unclassified Markdown: {name}')
    surface = cli_surface()
    for name in files:
        path = root / name
        if not path.is_file():
            errors.append(f'Missing public document: {name}')
            continue
        text = path.read_text()
        errors.extend(link_errors(path, text, root))
        errors.extend(prose_errors(name, text, versions))
        errors.extend(cli_errors(name, text, surface))
    for name in subprocess.check_output(['git', 'ls-files', '--cached', '*.md'], cwd=root, text=True).splitlines():
        if name not in files and HOME.search((root / name).read_text()):
            errors.append(f'{name}: personal home path in historical document')
    for path in (root / 'docs/v2').glob('*.md'):
        if 'Engineering history / dated acceptance record' not in path.read_text()[:300]:
            errors.append(f'{path.relative_to(root)}: missing historical banner')
    for name in ['README.md', 'docs/getting-started/quickstart.md', 'docs/reference/platform.md']:
        if props['dootah.public.version'] not in (root / name).read_text():
            errors.append(f'{name}: public version missing')
    return errors, len(files)


def examples():
    libs = ROOT / 'v2/publishing/build/install/dootah-publishing/lib'
    if not libs.is_dir():
        raise SystemExit('Build publisher first: v2/publishing/gradlew -p v2/publishing test installDist')
    subprocess.run(['java', '-cp', str(libs / '*'), 'groovy.ui.GroovyMain',
                    str(ROOT / 'tools/docs/examples.groovy'), str(ROOT)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--examples', action='store_true')
    args = parser.parse_args()
    errors, count = check()
    if errors:
        raise SystemExit('\n'.join(errors))
    if args.examples:
        examples()
    print(f'PASS: {count} public documents; local links, CLI spellings, versions/ABIs, paths and claim/secret checks')


if __name__ == '__main__':
    main()
