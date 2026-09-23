#!/usr/bin/env python3
"""Discover stable Kotlin candidates, test existing ABI implementations, generate receipts.

No consumer version is accepted from semver alone. A new patch is automatically
tested against existing implementations; only successful full suites are promoted.
Run from CI or locally, never during a consumer's Android build.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MAVEN = "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/maven-metadata.xml"


def properties(path):
    return dict(line.strip().split("=", 1) for line in path.read_text().splitlines()
                if line.strip() and not line.lstrip().startswith("#"))


def version_key(value):
    return tuple(map(int, value.split(".")))


def discover():
    with urllib.request.urlopen(MAVEN, timeout=30) as response:
        versions = [node.text for node in ET.fromstring(response.read()).findall("./versioning/versions/version")]
    # Every stable release in the supported K2 window, including feature releases.
    # Testing only the newest patches leaves ordinary existing applications behind.
    return sorted({v for v in versions if re.fullmatch(r"2\.[0-4]\.\d+", v)
                   and version_key(v) >= (2, 0, 20)}, key=version_key)


def implementation_hash(jar):
    digest = hashlib.sha256()
    with zipfile.ZipFile(jar) as archive:
        for name in sorted(n for n in archive.namelist() if n.endswith(".class")):
            digest.update(name.encode())
            digest.update(archive.read(name))
    return digest.hexdigest()


def source_hash():
    digest = hashlib.sha256()
    for module in ("dootah-compiler-plugin", "dootah-compiler-core", "dootah-contract"):
        for path in sorted((ROOT / module / "src").rglob("*")):
            if path.is_file() and (module == "dootah-compiler-plugin" or "test" not in path.parts):
                digest.update(str(path.relative_to(ROOT)).encode())
                digest.update(path.read_bytes())
    digest.update((ROOT / "gradle/compiler-adapters.properties").read_bytes())
    for path in ("dootah-compiler-plugin/build.gradle.kts", "dootah-compiler-core/build.gradle.kts",
                 "dootah-contract/build.gradle.kts", "Android-Dootah/gradle/libs.versions.toml"):
        digest.update((ROOT / path).read_bytes())
    return digest.hexdigest()


def run_probe(family, version, quick, output):
    module = "dootah-compiler-plugin" if family == "2.3" else f"dootah-compiler-plugin-kotlin-{family}"
    command = [str(ROOT / "gradlew"), f":{module}:test", f"-PdootahCompatibilityProbe={version}",
               "--console=plain", "--no-configuration-cache"]
    if quick:
        for test in ("ContractAgreementTest", "RealComposeCompatibilityTest", "IncrementalContractTest"):
            command += ["--tests", f"*{test}"]
    log = output / f"{version}-{family}-{'probe' if quick else 'full'}.log"
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
    report_directory = ROOT / module / "build/test-results/test"
    saved_reports = output / f"{version}-{family}-{'probe' if quick else 'full'}-results"
    if saved_reports.exists():
        shutil.rmtree(saved_reports)
    if report_directory.exists():
        shutil.copytree(report_directory, saved_reports, ignore=shutil.ignore_patterns("binary"))
    if result.returncode:
        return None
    reports = list((ROOT / module / "build/test-results/test").glob("TEST-*.xml"))
    counts = [ET.parse(report).getroot().attrib for report in reports]
    if not counts or any(int(c.get("failures", 0)) or int(c.get("errors", 0)) or int(c.get("skipped", 0)) for c in counts):
        return None
    count = sum(int(c["tests"]) for c in counts)
    if not quick and count < 100:
        raise RuntimeError(f"Incomplete compiler regression suite: {count} tests")
    jars = [p for p in (ROOT / module / "build/libs").glob("*.jar") if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
    jar = max(jars, key=lambda p: p.stat().st_mtime)
    ordering = {match for report in reports for match in
                re.findall(r"DOOTAH_ORDERING=(explicit|class-path)", report.read_text())}
    if len(ordering) != 1:
        raise RuntimeError("Real Compose test did not establish compiler plugin ordering")
    return {"family": family, "artifact": module, "tests": count,
            "ordering": ordering.pop(),
            "compiledAgainst": properties(ROOT / "gradle/compiler-adapters.properties")[family].split(',')[0],
            "implementationSha256": implementation_hash(jar)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="run candidate compiler tests")
    parser.add_argument("--check-evidence", action="store_true", help="refuse stale or incomplete release compatibility metadata")
    parser.add_argument("--promote", action="store_true", help="write generated compatibility metadata only after all candidates pass")
    parser.add_argument("--versions", help="comma-separated stable candidates; default: discover recent patches from Maven")
    args = parser.parse_args()
    if args.promote and args.versions:
        parser.error("Promotion requires the complete discovered matrix; --versions is for investigation only")
    if args.check_evidence:
        path = ROOT / "gradle/compiler-compatibility-evidence.json"
        if not path.is_file():
            sys.exit("Missing compatibility evidence; run the verified matrix before publishing")
        receipt = json.loads(path.read_text())
        if receipt.get("sourceSha256") != source_hash():
            sys.exit("Compiler implementation/tests changed since verification; rerun the matrix")
        members = properties(ROOT / "gradle/compiler-backends.properties")
        expected = {v: f for f, versions in members.items() for v in versions.split(',')}
        actual = {v: entry['family'] for v, entry in receipt['verified'].items()}
        if expected != actual or set(members) != set(properties(ROOT / "gradle/compiler-adapters.properties")):
            sys.exit("Compatibility metadata disagrees with verified implementations")
        if properties(ROOT / "gradle/compiler-features.properties") != {
                v: entry['ordering'] for v, entry in receipt['verified'].items()}:
            sys.exit("Compiler feature metadata disagrees with matrix evidence")
        for family in members:
            implementations = {entry['implementationSha256'] for entry in receipt['verified'].values()
                               if entry['family'] == family}
            if len(implementations) != 1:
                sys.exit(f"ABI family {family} was not verified with one identical implementation")
        print("Compatibility metadata matches the verified compiler implementation and matrix")
        return
    candidates = args.versions.split(",") if args.versions else discover()
    if any(not re.fullmatch(r"2\.\d+\.\d+", v) for v in candidates):
        parser.error("Only concrete stable Kotlin 2.x versions may be verified")
    print("Compiler candidates: " + ", ".join(candidates), flush=True)
    if not args.verify:
        if args.promote:
            parser.error("Promotion requires --verify")
        return
    definitions = properties(ROOT / "gradle/compiler-adapters.properties")
    verified = properties(ROOT / "gradle/compiler-backends.properties")
    output = ROOT / "build/compiler-compatibility"
    output.mkdir(parents=True, exist_ok=True)
    fingerprint = source_hash()
    results = {}
    for version in candidates:
        preferred = [family for family, members in verified.items() if version in members.split(",")]
        # This order only reduces probe attempts; it NEVER establishes compatibility.
        families = preferred + sorted(definitions, key=lambda f: abs(version_key(definitions[f].split(',')[0])[1] - version_key(version)[1]))
        for family in dict.fromkeys(families):
            print(f"Probing Kotlin {version} with ABI {family}", flush=True)
            if run_probe(family, version, True, output) is None:
                continue
            result = run_probe(family, version, False, output)
            if result is not None:
                results[version] = result
                print(f"PASS {version}: {family}, {result['tests']} tests", flush=True)
                break
        if version not in results:
            print(f"UNVERIFIED {version}: see {output}; no metadata promoted", flush=True)
    receipt = {"schema": 1, "sourceSha256": fingerprint, "candidates": candidates, "verified": results}
    (output / "results.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    if len(results) != len(candidates) or source_hash() != fingerprint:
        sys.exit("Matrix incomplete or compiler sources changed during verification; promotion refused")
    for family in {entry['family'] for entry in results.values()}:
        if len({entry['implementationSha256'] for entry in results.values() if entry['family'] == family}) != 1:
            sys.exit(f"ABI {family} changed implementation between compiler tests; promotion refused")
    if args.promote:
        lines = ["# GENERATED by tools/compiler_compatibility.py --verify --promote.",
                 "# Concrete verified members of ABI families; never inferred semver ranges."]
        for family in sorted({r['family'] for r in results.values()}):
            lines.append(family + "=" + ",".join(sorted([v for v, r in results.items() if r['family'] == family], key=version_key)))
        (ROOT / "gradle/compiler-backends.properties").write_text("\n".join(lines) + "\n")
        (ROOT / "gradle/compiler-features.properties").write_text(
            "# GENERATED from real Compose ordering tests.\n" +
            "".join(f"{v}={results[v]['ordering']}\n" for v in sorted(results, key=version_key)))
        (ROOT / "gradle/compiler-compatibility-evidence.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
        print("Generated compatibility metadata. No artifacts published.")


if __name__ == "__main__":
    main()
