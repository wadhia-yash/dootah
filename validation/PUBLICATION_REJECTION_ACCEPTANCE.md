# Publication rejection and real ngrok acceptance — 2026-09-20

PASS: the current Seal `genericDebug` publication now succeeds through ngrok.
The same bundle registers once and an identical retry returns `existing`.
This validates publication and HTTP delivery, not on-device OTA activation.

## Exact cause and proof

The running alpha.17 server used
`/Users/yashwadhia/.dootah/local/releases.json`. Its `publishers` map contained
`com.dootah.demo` and `com.ldlywt.note`, but no `com.junkfood.seal` entry.

The captured failing POST contained:

```json
{
  "channel": "production",
  "rolloutPercent": 100,
  "paused": false,
  "manifest": {
    "schemaVersion": 1,
    "appId": "com.junkfood.seal",
    "runtimeVersion": "9",
    "bundleVersion": 2,
    "enabled": true,
    "url": "https://d8b6-124-66-175-251.ngrok-free.app/artifacts/edf44115d5e4924d64068d476f7d75f1d4648985671f932e2e446dff13b8cf9d",
    "sha256": "edf44115d5e4924d64068d476f7d75f1d4648985671f932e2e446dff13b8cf9d",
    "images": []
  }
}
```

The actual manifest also includes its signature, omitted above for brevity.
No APK version bounds were sent. `manifest.appId = "com.junkfood.seal"` triggered
lookup of the absent `publishers[appId]` key. The original `ReleaseCatalog.text`
guard threw `IllegalArgumentException("Invalid release catalog")`; the controller
converted it to an empty 400, and the client discarded the error body and advised
retrying every non-200 response.

Evidence was collected before modifying trust configuration:

1. Read the captured ngrok request from its local inspection API, without printing
   its bearer token. Replayed its exact body and authorization against localhost:
   HTTP 400 with an empty body.
2. Ran the actual alpha.17 distribution's `ReleaseCatalog` classes against the
   existing catalog and current manifest. The existing catalog validated. The
   candidate failed at `ReleaseCatalog.validate:64 -> text:85 -> require:100`,
   the publisher lookup in that jar.
3. Added only the existing local public key to an in-memory catalog copy. The same
   alpha.17 validator verified the signature and accepted app ID, runtime 9 and
   bundle version 2. The probe made no catalog or artifact writes.
4. Confirmed that public key matches the key in Seal's current initialization
   source. The signing/private-key material was not sent to the server.

There is no request DTO mismatch: `DootahPublishTask` uses `PublicationClient`,
which uploads the named bytes and serializes a release object wrapping the signed
manifest. The controller reads JSON directly as `JsonNode`, and `PublicationStore`
passes the candidate through catalog/signature validation before checking artifacts
and immutable identities. The uploaded SHA already existed and matched its bytes.
The catalog's pre-existing version 2 belonged to another app, so it was not a
duplicate conflict. No compiler, runtime version or signing-schema mismatch was
found. The immediate blocker was missing operator trust configuration; both server
and client diagnostics obscured it.

## Correction

Server validation now emits deliberate `{code, message}` rejections. Missing app
trust has code `PUBLISHER_NOT_CONFIGURED` and identifies the required catalog
configuration. Other field, signature, artifact, malformed JSON and immutable
conflict cases have useful bounded reasons. Logs record the same controlled
diagnostic; raw parser/crypto exception text and filesystem paths are not returned.
Authentication still runs before mutation metadata parsing or validation.

The client reads a bounded HTTP error stream, surfaces the structured reason,
redacts its bearer token, removes control characters, and ignores arbitrary proxy
HTML or oversized/malformed bodies. Empty legacy errors get status-specific
guidance instead of an unconditional instruction to repeat a rejected request.

The operational fix was to add the independently verified public key under the
exact app ID in the existing external catalog, under the catalog lock and with an
atomic replacement. Existing publishers and releases were preserved. The server
does not auto-enroll publishers or trust keys submitted in release requests.

Changed source/test files for this investigation:

- `dootah-server/src/main/java/dev/dootah/server/PublicationRejection.java`
- `dootah-server/src/main/java/dev/dootah/server/PublicationController.java`
- `dootah-server/src/main/java/dev/dootah/server/PublicationStore.java`
- `dootah-server/src/main/java/dev/dootah/server/ReleaseCatalog.java`
- `dootah-server/src/test/java/dev/dootah/server/PublicationTest.java`
- `dootah-gradle-plugin/src/main/kotlin/dev/dootah/gradle/internal/PublicationClient.kt`
- `dootah-gradle-plugin/src/test/kotlin/dev/dootah/gradle/internal/PublicationClientTest.kt`
- `docs/CI_PUBLISHING.md`
- `DOOTAH_CURRENT.md` (local project-state document)
- This acceptance report.

Pre-existing compiler/contract and variant-workflow changes were retained. No
compiler logic or acceptance-application source/build files were edited.

## Tests

Six server regressions cover missing publisher trust after authenticated artifact
uploads, explicit provisioning followed by registration/idempotent retry, malformed
JSON without secret echo, named field constraints, missing artifacts/signatures,
immutable conflicts preserving catalog state, and auth preceding validation.
Three client regressions exercise real HTTP error streams through an in-process
HTTP fixture: actionable missing-key reason, safe handling/redaction of proxy and
structured responses, and auth/conflict/oversized-body behavior. Production URL
validation still requires HTTPS; the HTTP test fixture uses the existing transport
injection seam.

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew \
  dootahCompilerMatrixCheck :dootah-contract:test \
  :dootah-android:testDebugUnitTest :dootah-server:test \
  --max-workers=2 --continue --console=plain
```

| Suite | Passed |
| --- | ---: |
| Compiler Kotlin 2.0.20 | 165 |
| Compiler Kotlin 2.3.20 | 165 |
| Compiler core | 1 |
| Gradle plugin | 53 |
| Contract | 67 |
| Android | 194 |
| Server | 39 |
| Total | 684 |

Zero failures, errors or skips. `dootahRealComposeCheck` also passed. Focused
publication tests passed before the broader suite. `git diff --check` passed.

## Real publication

New local-only tooling/server version: `0.1.0-publication-diagnostics.1`.
It was unused before staging. All SDK modules were staged together to Maven Local,
and a separate server distribution was built and extracted outside the repository.
No immutable alpha release was published or overwritten.

The local server was restarted on `127.0.0.1:8080`, retaining the same catalog,
artifact directory, publishing credential and ngrok origin. Before provisioning
the key, the new server and the actual Gradle task both surfaced:

```text
Publication rejected: PUBLISHER_NOT_CONFIGURED: No publisher public key is
configured for appId 'com.junkfood.seal'; configure publishers[appId] in the
server catalog with the trusted public key (HTTP 400)
```

A temporary init script selected the new Gradle plugin version without changing
Seal's build file. With the same externally supplied signing key and bearer token:

```sh
./gradlew :app:dootahPublish -I /tmp/dootah-publication-acceptance.0tRg2Y/consumer.init.gradle \
  -PdootahServer=https://d8b6-124-66-175-251.ngrok-free.app \
  -PdootahChannel=production -PdootahRollout=100 \
  -PdootahVariant=genericDebug --no-configuration-cache --console=plain
```

After provisioning, the task printed:

```text
Dootah publication registered: a5c1fe2e71b50f3eba56e697f20c6258ec37027a0ef76e0ab7d8edc42b583110
BUILD SUCCESSFUL in 10s
```

An identical Gradle retry printed `existing` with the same identity and succeeded
in 9s. Ngrok recorded artifact PUT 200 and release POST 200. An HTTPS update check
for the app/runtime/channel returned bundle 2, and downloading its artifact through
ngrok produced the original SHA-256. Catalog release count is now 2: the original
IdeaMemo release plus the new Seal release, with no duplicate from the retry.

The baseline contract checksum remains
`ae7b89a7bce1947cc6dd301d9b08fc2a78b42ee149167a15542b96a2c3c9ce75`.
The bundle remains 463,338 bytes with the original `edf44115...b8cf9d` hash above.
Seal remains Kotlin 2.0.20 and `genericDebug`; no contract recording, APK assembly,
APK installation or UI edit was performed. RuntimeVersion remains 9. Comparison
verified all 1,591 versioned alpha-repository files were unchanged.

The validation server remains running from:
`~/.dootah/local/server-publication-diagnostics.1/dootah-server-0.1.0-publication-diagnostics.1/bin/dootah-server`.
The original alpha.17 installed distribution was not overwritten.
Logs and the pre-change catalog backup are in
`/tmp/dootah-publication-acceptance.0tRg2Y/`; suite logs are
`/tmp/dootah-publication-focused.log` and `/tmp/dootah-publication-full.log`.

Separate observation for subsequent device work: current Seal `App.kt` has a
`manifestUrl` beginning with `ttps://`. That source was left unchanged, and the
installed APK's configuration was not inspected. Publication success does not
establish that the installed device can fetch or activate this update.
