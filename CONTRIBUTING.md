# Contributing to Dootah V2

Start with the [public docs](docs/README.md) and [support boundary](docs/reference/support.md).
Keep changes small and generic. Explain the problem, resulting behavior, tests and
remaining limits in the PR. Do not add app-specific exceptions or weaken a refusal
check to pass an example. No CLA or DCO/sign-off policy has been adopted here.

## Environment and repository

Use JDK 21, Node 24 (CI pins 24.20.0), Python 3.9+, Git and the checked-in V2 Gradle
9.3.1 wrappers. Android SDK 36, build-tools 36.0.0, NDK 27.1.12297006 and CMake 3.22.1
are needed for SDK production. Set `JAVA_HOME` and `ANDROID_HOME`. Full gates also
need Docker/Compose, Bash, curl, OpenSSL and GnuPG. See [CI resource estimates](v2/ci/README.md).

| Path | Purpose |
| --- | --- |
| `v2/android-sdk/` | Runtime, post-compile Gradle instrumentation, tests, packaging |
| `v2/portable/` | Shared installed/publisher PortableProgram validation |
| `v2/publishing/` | Read-only analyzer, customer CLI, contract/publishing tests |
| `v2/native-consumer/` | Instrumentation fixture, not the customer quickstart |
| `v2/runtime-spike/` | Pinned Expo/RN producer and maintained patch; historical name |
| `v2/cloud/` | Cloud authorization, contracts, release control and telemetry |
| `v2/server/` | Pinned xprem community composition and audit |
| `v2/deploy/` | Single-host deployment and lifecycle acceptance |
| `v2/ci/` | Required-suite manifest, isolated gates, scanner/signing checks |
| `docs/`, `v2/examples/` | Current public docs and small Kotlin example |
| `docs/v2/` | Dated engineering/evidence records; see [history](docs/history/README.md) |

There is no root Gradle build. Each V2 project under `v2/` has its own wrapper. Historical
V1 is preserved on the `archive/dootah-v1-final` tag; see [history](docs/history/README.md).

## Focused checks

Run from the repository root:

```sh
python3 tools/docs/validate.py
v2/publishing/gradlew -p v2/publishing test installDist
node --test v2/publishing/customer.test.mjs v2/publishing/portable.test.mjs
python3 tools/docs/validate.py --examples
python3 -m unittest discover -s tools/docs -p 'test_*.py'
```

The example check uses the built publisher distribution to run the actual analyzer
and installed PortableProgram over both documented Kotlin files. It executes the
regenerated bounded expression in Node as a semantic check, not sandbox/device proof.

For SDK/instrumentation changes run `v2/android-sdk/build-sdk.sh`; it runs runtime,
native-health, plugin and real Compose fixture gates. The V2 Compose gate is
`v2/android-sdk/tests/compose-hook.sh`; historical `dootahRealComposeCheck` belongs to
V1. For packaging changes run the [release checks](v2/android-sdk/release/README.md).
Cloud integration tests require disposable DB/xprem fixtures: use the full server
gate instead of treating a fixture-less run with skipped tests as passing.

## Required acceptance

From a clean committed tree run the three isolated [V2 required gates](v2/ci/README.md#isolation-and-local-execution).
`v2/ci/required-suites.json` defines mandatory suites and named tests. Missing/empty
reports, incomplete runs, failures and unexpected skips fail. The only exclusions
are the four explicitly named xprem Azure tests; never count them as passes. Restore
runs in every server gate, including PRs. Preserve report-to-source-commit binding.

Run relevant module tests first, then broader required gates for implementation or
build/config changes. Documentation-only work can use the [documentation checks](docs/development/README.md)
with an explicit statement of what was rerun. Hosted Actions status must be reported
separately from local commands. A real OTA claim requires baseline install, a supported
source edit, publication with no rebuild/reinstall, device verification and relevant
fallback/rollback checks. Compiler output and builds alone are not that evidence.

xprem is pinned at `b46e13569f5734a66ed903f75f51b87b78e80c1b`. The composition exports
341 reviewed byte-identical upstream files and audits the complete build/test graph
for zero EE dependencies. Do not copy in the full upstream tree or bypass
[the license boundary](v2/server/XPREM-LICENSE.md). Dependency changes require renewed
pin, license and graph review; architecture-dependent package counts can differ.

## Security-sensitive contributions

Treat signing/trust, PortableProgram parsing/bounds, capability dispatch, sandbox
lifecycle, source identity, fallback/health, Cloud authorization, private files,
enrollment, migrations and persistence as security-sensitive. Add negative regression
coverage and describe trust implications. Never upload secrets, raw acceptance logs,
private retained source/classes, APKs, database dumps or operator state. Public
certificates are different from private keys, but review them deliberately.

Read [the security model](docs/security/README.md). Report suspected vulnerabilities
privately as described in [SECURITY.md](SECURITY.md), not in a public issue or pull
request; no response SLA is promised. Never put exploit details or credentials in a
public issue.

Before committing, run `git diff --check`. Use focused commits and preserve evidence;
do not rewrite history, commit temporary corpus edits or add generated/AI attribution.
