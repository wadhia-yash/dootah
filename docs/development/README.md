# Documentation maintenance

The [public documentation inventory](public-docs.json) explicitly separates maintained
product/operational guides from engineering records. New Markdown files outside the
history/evidence roots must be classified; the validator fails on unclassified files.
History paths remain accessible, but public navigation starts at [docs](../README.md).

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
historical document banners and deliberate boundaries, not the truth of every prose
sentence. External links are not fetched. It never contacts Cloud or publishes anything.

`--examples` requires JDK 21, Node 24 and the publisher distribution built above. It
runs both exact Kotlin example files through SourceAnalyzer, LogicLowering and
PortableProgram, verifies stable identity/source skeleton and evaluates the generated
expressions for baseline/OTA output. It is not an Android sandbox or device OTA test.

For changes to integration snippets, build the exact fenced snippets in a temporary
consumer using a reviewed local public-version stage:

```sh
python3 tools/docs/build_example.py --stage "$HOME/dootah-work/sdk-stage-alpha-1"
```

It checks Debug retention, a Release build, one installed hook in each, standalone
CLI import and supported-edit analysis. It generates/discards a test certificate and
removes its private workspace. It uses your normal Gradle cache unless you explicitly
set a fresh `GRADLE_USER_HOME`; the receipt does not claim cache isolation. This is
build/analysis evidence, not a phone or Cloud publication test. For full secret scanning use the existing [security gate](../../v2/ci/README.md),
which scans all tracked bytes including history; the lightweight doc check only rejects
obvious private-key/credential literals and is not a replacement for Gitleaks.

## Evidence policy

Public documentation is addable normally. `docs/v2/evidence/` remains ignored for
**new** files; already tracked, reviewed sanitized receipts remain versioned. Add a
new curated receipt only after reviewing its contents and using `git add -f` for that
specific path. Do not force-add directories or raw acceptance workspaces.

Private planning/handoff files remain ignored. Build directories, local SDK paths,
node_modules, private keys, environment files, publisher sessions/tokens/tickets,
retained contracts/releases and operator state remain ignored or outside Git.
A `.gitignore` is not a secret scanner; inspect every staged diff.
