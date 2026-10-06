# Dootah alpha customer workflow

CLI and private-file reference for public SDK `0.1.0-alpha.1`, development SDK
`0.7.5-local`, Runtime ABI 2 / Logic ABI 1. Start with the authoritative
[V2 quickstart](../../docs/getting-started/quickstart.md) for the complete workflow.
This reference supplies detailed CLI, retention and enrollment semantics. It does
not deploy Cloud or claim public Maven availability.

## Install the tool once

The alpha tool is built from the repository root with JDK 21:

```sh
v2/publishing/gradlew -p v2/publishing test installDist
```

Copy the entire `v2/publishing/build/install/dootah-publishing` directory wherever you keep tools,
and add its `bin` directory to PATH. Run `dootah --help`. The copied directory needs
only JDK 21 and Node 24: no Android workspace, Gradle wrapper, fixture app, npm,
Metro, Hermes exporter or repository checkout at execution time. `distZip` produces
the same distribution as an archive. Consumer Android compilation never invokes it.
Direct xprem publication remains a legacy operator option; the customer path is Cloud.

Cloud must already be running and your user must be provisioned. The existing Cloud
operator configures PostgreSQL, user provisioning and a dedicated xprem delivery
binding/public signing certificate for each app. Creating an app is an authenticated
customer action; provisioning its signing/storage backend is an operator action.
There is no customer DB credential or hidden admin endpoint. See
[Cloud API](../../docs/v2/CLOUD_API.md) for these existing operator prerequisites.

## Authenticate and create the app

Use HTTPS outside local development. An explicitly requested session file is the
only session storage; it is origin-bound, expires after eight hours, and has mode
0600. There is no automatic global credential cache. Files must be owned by the
current POSIX user; symlinks, group/world-readable files and overwrites are rejected.
Keep credentials in a private directory excluded from source control. Revocation and
Cloud role/scope checks apply on every request.

```sh
export DOOTAH_CLOUD_URL=https://cloud.example.com
# Supply the password through your secret manager as a mode-0600 file.
dootah login --email you@example.com --password-file /private/password --session-file /private/session
dootah org list --session-file /private/session
dootah org create --name 'My organization' --session-file /private/session
export DOOTAH_ORGANIZATION='RETURNED_ORGANIZATION_ID'
dootah app create --name 'My Android app' --session-file /private/session
dootah app list --session-file /private/session
```

`DOOTAH_CLOUD_PASSWORD` is also accepted for login. Password/token values are never
command-line arguments or console output. `--token-file` and `DOOTAH_CLOUD_TOKEN`
authenticate subsequent API commands; owner/session-only operations stay session-only.
List commands return API pagination cursors; pass `--after` for the next page.

## Integrate, build and retain

Use a fresh public-version Maven stage built from the current checkout until
public publication is authorized. The current staged plugin includes release retention;
there is no need for a plugin composite build or a frozen older stage. Follow the
[quickstart repository setup](../../docs/getting-started/quickstart.md#current-pre-publication-setup).
The plugin supplies its matching runtime dependency automatically. Source development
uses `0.7.5-local`; the staged public candidate is `0.1.0-alpha.1`.

Apply `dev.dootah` to the application module, use `DootahApplication` as its Application
superclass, and configure:

```groovy
dootah {
    appId = '<Cloud app ID>'
    updateUrl = 'https://cloud.example.com/manifest'
    channel = 'development'                 // desired Cloud environment name
    runtimeVersion = 'my-installed-runtime-1'
    publicCertificate = file('update-certificate.pem') // operator-supplied PUBLIC cert
}
```

Toolchain floor: compileSdk 36, AGP 8.9.1+, minSdk 29, arm64; the current acceptance
uses AGP 8.12.0 / Kotlin 2.1.20 / Gradle 9.3.1. No Activity base class, per-screen
annotation, compiler plugin, JavaScript application or consumer npm project is needed.

Before editing Kotlin, retain the native baseline with the **variant-specific** task:

```sh
./gradlew :app:dootahRetainDebugRelease
# For a non-debug, possibly R8-minified release variant:
./gradlew :app:dootahRetainReleaseRelease
# A flavor named demoRelease has task dootahRetainDemoReleaseRelease.
```

The task builds the APK and obtains instrumented project classes through AGP's public
`ScopedArtifact.CLASSES` API. It reads the APK with `SingleArtifact.APK` and the public
built-artifacts loader. No AGP intermediate paths, IDs or hashes are user inputs.
It writes `app/build/outputs/dootah/<variant>/retained/`:

| File | Purpose |
| --- | --- |
| `accepted.apk` | Exact native baseline, manifest, DEX identities and installed capability asset |
| `release.json` | APK SHA-256, source hashes/roots, variant, application/Cloud app IDs, channel, runtime version, SDK/ABI/Kotlin/AGP/Gradle versions |
| `classes/`, `classHashes.json` | Instrumented project class files before R8, with sorted relative-path SHA-256 mapping |
| `hooks.json` | Actual instrumented owners, methods, JVM descriptors and identities |
| `capabilities.json` | Unchanged installed capability contract copied from the APK |
| `sources/` | Only Kotlin files in this variant's Java/Kotlin source roots, relative to the application module |

It does **not** copy dependencies, the project tree, `.git`, Gradle caches, signing
material or arbitrary build outputs. Generated Kotlin in declared variant source
roots is included. Sources and classes are private intellectual property. Archive
the complete directory privately before another native build; the task replaces its
output when inputs change. Determinism means identical retained inputs yield identical
record contents/hashes; rebuilding an APK with new embedded IDs, timestamps or signing
bytes naturally changes its digest. No wall-clock timestamp is added by retention.

Current bounds: exactly one APK output per variant; multiple ABI/density APK outputs
are refused. Source roots outside the app module are refused. Library-module hooks
and unsupported parameter forms remain outside the frozen product contract. A record
does not attest that an APK was installed: install/accept **that exact APK** in your
native-release process and retain its hash. No device installation is performed by
the tool. Re-running retention after source changes creates a different native baseline.

## Import and register the installed contract

```sh
dootah release import \
  --record /private/native-release/release.json \
  --source-root /work/my-app/app \
  --config /private/my-release/publish.json
dootah env create --config /private/my-release/publish.json --session-file /private/session
dootah contract register --config /private/my-release/publish.json --session-file /private/session
dootah env list --app <Cloud-app-id> --session-file /private/session
```

The config directory must exist. Import verifies APK/class/source hashes, matches
source identities against instrumented methods and packaged DEX, and pins a private
source-bearing installed contract. Neither config nor baseline contract is overwritten.
`--source-root` is the current application module where you will edit Kotlin, not an
AGP intermediate directory. Analysis reads the retained source roots and refuses
added/removed Kotlin files or changed signatures outside the existing grammar.

**Environment creation and contract registration are one operation.** Existing Cloud
semantics require an immutable runtime/contract at creation. Choose the channel name
before building, then create its environment after retention. The CLI derives JVM
parameter types/nullability automatically, excludes R8-removed identities and forms
outside Logic ABI 1, and sends only `{capabilities, functions}`. No source code or full
private contract is uploaded. Existing matching registration succeeds idempotently;
different app, name, runtime, ABI or contract fails. A new incompatible native baseline
needs an appropriate new environment/runtime, not an overwritten contract.

Registration saves the Cloud target into the publisher config. It does not save the
login credential there. Create a scoped publishing credential through the owner session:

```sh
dootah token create --app <app-id> --environment <environment-id> \
  --scopes app:read,release:read,release:publish,release:control \
  --days 7 --out /private/publisher.token --session-file /private/session
```

The token is written once, not printed. `token list`, `token revoke --id …`, and
`logout --session-file …` use the existing APIs. List output never contains token secrets.

## Edit, analyze, publish and control

Make a supported Kotlin body edit; for example change a `BasicText` string using its
existing String input. Do not rebuild or refresh the retained baseline for an OTA edit.

```sh
dootah analyze /private/my-release/publish.json
dootah publish /private/my-release/publish.json --token-file /private/publisher.token \
  --revision '<source-revision>' --key '<stable-publication-request-id>'
dootah releases --environment <environment-id> --token-file /private/publisher.token
dootah operation inspect --id <operation-id> --token-file /private/publisher.token
dootah release inspect --id <release-id> --token-file /private/publisher.token
dootah rollout --id <release-id> --version <current-version> --percentage 25 --token-file /private/publisher.token
dootah pause --id <release-id> --version <current-version> --token-file /private/publisher.token
dootah resume --id <release-id> --version <current-version> --token-file /private/publisher.token
dootah rollback --id <release-id> --version <current-version> --key '<rollback-request-id>' --token-file /private/publisher.token
```

Cloud publication returns a durable operation, not an immediate delivery guarantee.
Wait for `succeeded`/release `ready` before rollout. Inspect the latest version before
each control mutation; stale versions return 409. A signed rollback is asynchronous;
inspect its operation too. Reuse the same explicit idempotency key for an ambiguous
publish/rollback retry. Publication never enables rollout automatically. Changed
unsupported Kotlin refuses the entire publication while the native app remains valid.

## Telemetry enrollment

Portable code cannot enroll itself. Do not put a publisher token or operator credential
in the APK. The native Application calls the existing SDK method using a short-lived
one-use ticket obtained from a legitimate authenticated customer backend.

The installable tool includes `tooling/enrollment.mjs`. Backend code can import
`mintEnrollmentTicket(config, environmentId)`. Configure a backend-only token limited
to the app/environment with `release:publish`. Authenticate and authorize your app user
**before** calling the helper; rate-limit the backend route and return only its ticket
over HTTPS. The helper is not a public endpoint or anonymous enrollment mechanism.
It does not log credentials. Backend authentication remains the integrator's responsibility.

For an operator-driven development flow:

```sh
dootah enrollment ticket --environment <environment-id> \
  --token-file /private/publisher.token --out /private/one-use.ticket
```

Deliver that ticket through your authenticated native integration, then:

```kotlin
// Application/native code, after your authenticated backend supplies a fresh ticket.
// Rendering and app startup must proceed regardless of enrollment success.
application.enrollTelemetry(ticket) { enrolled ->
    // Best-effort status only. Do not log the ticket or block native navigation.
    // On failure, request a new ticket through the authenticated backend later.
}
```

Here `application` is your `DootahApplication` instance. The SDK exchanges the ten-minute
single-use ticket for the existing 30-day installation credential in app-private storage.
Re-enrollment rotates credentials. The backend must mint a fresh ticket for renewal;
there is no zero-code anonymous enrollment or automatic long-term renewal. Network,
ticket-expiry and enrollment failures remain non-fatal. No SDK runtime change is made.

## Local HTTP is debug-only

For local Cloud CLI commands use `--url http://127.0.0.1:<port> --allow-local-http`.
Loopback HTTP is opt-in; remote cleartext URLs, redirects and credential-bearing URLs
are refused. CLI flags cannot change an APK's network policy.

For Android, keep `updateUrl` HTTPS and optionally set `debugUpdateUrl` to a loopback
URL plus `allowLocalHttp = true`. The plugin uses that URL only for debuggable variants.
A non-debuggable variant with an HTTP update URL fails its asset-generation/build task.
Use `adb reverse` for a device, or Android emulator host alias `10.0.2.2`.

Add this to **`src/debug/AndroidManifest.xml`** only:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/dootah_local_network" />
</manifest>
```

And **`src/debug/res/xml/dootah_local_network.xml`**:

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
        <domain includeSubdomains="false">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

Keep only the local addresses you use. Android cannot restrict these domain rules to
one port; all ports on the listed hosts are allowed in that debug variant. Merge with
an existing debug config deliberately. The plugin does not inject a generic config
over an application's custom policy. Do not enable global production cleartext traffic.

## Maintainer-only local acceptance

`python3 v2/publishing/acceptance.py --stage <current-public-stage> --xprem-binary <audited-server-binary>` creates
fresh isolated Postgres/xprem/Cloud state, a new Android app, and a copied standalone
CLI distribution. Set `JAVA_HOME` (21) and `ANDROID_HOME` first. It runs customer
creation, Debug/R8 retention/import, contract registration, analysis, local signed
publication, controls, rollback, enrollment-ticket issuance and the complete existing
Cloud suites with all fixtures supplied. It fails on missing prerequisites or skipped
Cloud tests. Containers/network are removed even on failure; private logs, APKs and
credentials stay in the mode-0700 temporary workspace for inspection, never in Git.
No external Cloud deployment, public artifact upload or phone OTA is involved.
