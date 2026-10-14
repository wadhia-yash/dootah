# Documentation maintenance

The [public documentation inventory](public-docs.json) lists maintained
product and operational guides. Every public Markdown file must be classified; the validator fails on unclassified files.
Public navigation starts at [docs](../README.md).

From the repository root:

```sh
python3 tools/docs/validate.py
python3 -m unittest discover -s tools/docs -p 'test_*.py'
v2/publishing/gradlew -p v2/publishing test installDist
python3 tools/docs/validate.py --examples
```

Validation checks local Markdown destinations and heading fragments, machine-specific
paths in public guides, personal home-directory paths in all tracked Markdown (generic
patterns, no hardcoded user name), SDK/ABI values against source, forbidden obsolete
setup/marketing claims and CLI command/option spellings against live help/parser
declarations. It checks
document classification and deliberate boundaries, not the truth of every prose
sentence. External links are not fetched. It never contacts Cloud or publishes anything.

`--examples` requires JDK 21, Node 24 and the publisher distribution built above. It
runs both exact Kotlin example files through SourceAnalyzer, LogicLowering and
PortableProgram, verifies stable identity/source skeleton and evaluates the generated
expressions for baseline/OTA output. It is not an Android sandbox or device OTA test.

For changes to integration snippets, build the exact fenced snippets in a temporary
consumer using a reviewed local public-version stage:

```sh
python3 tools/docs/build_example.py --stage "$HOME/dootah-work/sdk-stage-alpha-2"
```

It checks Debug retention, a Release build, one installed hook in each, standalone
CLI import and supported-edit analysis. It generates/discards a test certificate and
removes its private workspace. It uses your normal Gradle cache unless you explicitly
set a fresh `GRADLE_USER_HOME`; the receipt does not claim cache isolation. This is
build/analysis evidence, not a phone or Cloud publication test. For full secret scanning use the existing [security gate](../../v2/ci/README.md),
which scans all tracked bytes including history; the lightweight doc check only rejects
obvious private-key/credential literals and is not a replacement for Gitleaks.

## Evidence policy

Keep internal reports, investigations, raw logs, screenshots and operator state in a
private archive outside the repository. Maintain concise public validation scope in
[status](../reference/status.md), including unsuccessful cases and limitations.

Focused recorded-observation fixtures may support tests, but must state their date,
source digest and historical scope. They must not be presented as fresh device tests
or replace executable security regressions. Preserve required third-party attribution.

Never stage secrets, retained customer code/APKs, local configuration or private
handoffs. Review explicit staged paths; `.gitignore` is not a secret scanner.
