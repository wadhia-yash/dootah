# CI-friendly publication — Phase 6.6

## One command

In an app that applies `dev.dootah`, bump the existing `dootah.bundleVersion`
after a normal source change, then run:

```sh
export DOOTAH_SIGNING_KEY_FILE=/external/secrets/publisher.pem
export DOOTAH_PUBLISH_TOKEN='<server publishing bearer token, at least 32 characters>'
./gradlew :app:dootahPublish \
  -PdootahServer=https://localhost:8443 \
  -PdootahChannel=production -PdootahRollout=100 \
  -PdootahMinAppVersion=2 -PdootahMaxAppVersion=2 \
  --no-configuration-cache --console=plain
```

The APK-version bounds are optional and inclusive. Server origin, channel and
percentage are explicit required inputs; there is no implicit production/100%.
The external Ed25519 PKCS#8 PEM and bearer token come from environment variables,
not task inputs, command arguments, logs, artifacts or build-cache entries.
Use Java 17 and the existing pinned Kotlin/Android setup. The app's committed
`app/dootah/contract.json` must describe the **installed** APK. Record that contract
in a separate `dootahRecordContract` invocation after the initial APK build;
never regenerate it from later OTA source in a publishing pipeline. Every task in
the workflow reads one variant -- the single debug variant where a module builds
one, otherwise the variant named by `dootah { variant = "..." }` or
`-PdootahVariant=`.

The task graph checks inputs, extracts source, validates the installed contract,
compiles the OTA bundle, packages required images and signs. No assemble/package/
install APK task is required. Missing/empty contract is fatal for publication;
the existing standalone bundle adoption behavior remains unchanged.
`dootahBundle` remains available. Supplying `dootahServer` makes its output URLs
content-addressed for this server and overrides the existing `bundleUrl` only
for that invocation. `dootahPublish` always depends on building/signing, so stale
output cannot bypass validation. Publication is never Gradle-cached or skipped.

## Local server setup

Build the distribution with `:dootah-server:installDist`. Provision an external
catalog once with the existing `publishers` public-key map and empty `releases`
array (see `CHANNELS_ROLLOUT.md`). Only this initial trust configuration is manual;
publication and rollout changes require no catalog editing.

Provision that trust entry for **each new app ID** before its first publication:
`publishers[manifest.appId]` must contain the base64-encoded raw 32-byte Ed25519
public key matching the publisher's external signing key and the key trusted by
the installed APK. A valid publishing token authorizes uploads but does not enroll
a new app or bypass signature verification. Preserve existing publisher entries
and releases when adding another app; never reset the catalog to resolve a
registration failure. Make operator catalog edits while the server is stopped,
or under its catalog file lock with an atomic replacement.

Configure the server process with:

- `DOOTAH_CATALOG`: external catalog file.
- `DOOTAH_ARTIFACTS`: external immutable object directory.
- `DOOTAH_PUBLIC_URL`: HTTPS origin devices use, e.g. `https://localhost:8443`.
- `DOOTAH_PUBLISH_TOKEN`: a random bearer token of at least 32 characters.
- Existing Spring TLS configuration (`SERVER_SSL_ENABLED`, `SERVER_SSL_KEY_STORE`,
  `SERVER_SSL_KEY_STORE_PASSWORD`). Bind remains loopback by default.

The publisher trusts normal JVM TLS roots; for a local test certificate, provision
an external JVM truststore and use `javax.net.ssl.trustStore`/`trustStorePassword`.
Android must already trust the endpoint in its installed APK. No TLS bypass exists.
The server never receives the publisher private key. Its existing public key
verifies the publisher signature independently of bearer authentication.

## HTTP API

All mutation requests require `Authorization: Bearer <token>`. With an absent or
short token, mutations are disabled (401). Device checks and artifact GETs remain
public. This single local operator credential authorizes all catalog publishers;
there are no accounts or per-user roles.

| Endpoint | Body / behavior |
|---|---|
| `PUT /publish/artifacts/{sha256}` | Raw bytes; validates SHA-256 and 8 MiB limit, syncs a temporary file, atomically installs it; returns `{status:"stored",sha256}`. |
| `POST /publish/releases` | `{channel,rolloutPercent,paused,manifest,minAppVersion?,maxAppVersion?}`; original already-signed manifest; returns `{status:"registered" or "existing",identity}`. |
| `PUT /publish/releases/{identity}/rollout` | `{rolloutPercent,paused}`; returns `{status:"configured",identity}`. |
| `GET /artifacts/{sha256}` | Verified immutable bytes, long immutable cache lifetime. |
| `GET /updates/check` | Existing Phase 6.5 device selection API, unchanged. |

Metadata is bounded to 64 KiB. Invalid publications return an error; version/
identity conflicts return 409, missing objects 404. Transport/client errors
produce nonzero Gradle exit status; response bodies cannot expose the bearer
secret or a local private key. The publisher checks each upload receipt and the
expected canonical signed release identity in the registration receipt.

Validation rejections carry a bounded JSON envelope, `{code, message}`. The server
logs the same deliberate diagnostic, and Gradle includes it in the failure. Raw
parser/cryptographic exception text and proxy HTML are not exposed as diagnostics.
Older servers with empty error responses still fail with status-specific guidance.

| Code | Action |
|---|---|
| `PUBLISHER_NOT_CONFIGURED` | Add the independently trusted public key for the exact manifest app ID to the server catalog. |
| `SIGNATURE_INVALID` | Check that the signing key matches that app's configured publisher key. |
| `MALFORMED_METADATA` / `INVALID_RELEASE_METADATA` | Correct the named JSON or field constraint. |
| `ARTIFACT_NOT_FOUND` / `ARTIFACT_HASH_MISMATCH` | Check uploaded bytes and the server's artifact directory. |
| `ARTIFACT_URL_MISMATCH` | Align `dootahServer` and `DOOTAH_PUBLIC_URL`, then rebuild/sign the bundle. |
| `IMMUTABLE_PUBLICATION_CONFLICT` | Retry the exact original release, or use a new bundleVersion for different content. |

Do not repeatedly retry an unchanged validation failure. In particular, successful
artifact upload followed by `PUBLISHER_NOT_CONFIGURED` means bearer authentication
worked and the app-specific trust entry is missing; it is not a Kotlin/compiler or
bundle determinism failure.

For rollout expansion, use the identity printed by publication:

```sh
curl --fail-with-body --request PUT \
  --header "Authorization: Bearer $DOOTAH_PUBLISH_TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"rolloutPercent":100,"paused":false}' \
  "https://localhost:8443/publish/releases/$DOOTAH_RELEASE_ID/rollout"
```

Use a trusted CA (`--cacert` for the local certificate), never `--insecure`.
No build, signing or payload transfer is needed for this operation. Channel and
APK bounds belong to release registration; publication retries cannot move a
release to another channel or change bounds. These are existing HTTPS control-
plane semantics, not newly publisher-signed fields.

## Ordering, immutability and retry semantics

All local files are checked before upload. Only the manifest's bundle and images
are sent. Uploads use `/artifacts/<SHA-256>`; an existing identical object is a
successful no-op, and different bytes cannot overwrite it.

**Registration is last.** The server independently verifies signature, metadata,
local artifact presence/hash, exact public URLs, and equality of the bundle's
hashed image-dependency header with the manifest image set. It does not fetch
arbitrary URLs. A missing or corrupt image prevents registration. Android still
performs its existing signature/hash/image-decoding/lifecycle checks.

A process lock plus file lock serializes writers; a synced temporary catalog is
atomically renamed only after complete validation. Concurrent/restarted retries
cannot create duplicate releases or discard another registered release. Device
readers see either the previous complete catalog or the new complete catalog.
No partial update is selectable. Files uploaded before a failure may remain
unreferenced; this finite milestone adds no artifact garbage-collection service.

Release identity is the existing SHA-256 of canonical signed fields. Identical
registration succeeds with `existing`. A different signed payload with the same
app/runtime/channel/bundleVersion, changed channel for the same identity, or
changed immutable targeting bounds fails. A retry **does not reset** rollout or
pause controls changed since the first successful publication.

If a response is lost or malformed after the server commits, the publisher
reports failure because it cannot know success. Retrying the same source,
version, server and key resolves that ambiguity without duplication. A failed
registration before commit leaves the catalog unchanged. Execution validity is
still decided on-device: a signed, hash-valid bundle can fail initialization and
trigger existing rollback/quarantine.

## GitHub Actions example

[examples/ci/dootah-publish.yml](../examples/ci/dootah-publish.yml) is a host-app
workflow example, not an enabled workflow in this SDK repository. It checks out
the app and a pinned Dootah tooling commit, sets up Java/Android, builds tooling,
and publishes non-interactively with CI secrets. Configure repository variables
`DOOTAH_TOOLING_REPOSITORY`, `DOOTAH_TOOLING_COMMIT`, `DOOTAH_SERVER_URL`, and
secrets `DOOTAH_PRIVATE_KEY_PEM`, `DOOTAH_PUBLISH_TOKEN`. A private tooling repo
also needs a checkout credential. The workflow needs a runner that can reach the
configured HTTPS service; a GitHub-hosted runner cannot reach your laptop's
localhost. No cloud deployment or actual hosted Actions run is claimed here.

The example uses the documented interfaces from
[checkout](https://github.com/actions/checkout),
[setup-java](https://github.com/actions/setup-java), and
[setup-android](https://github.com/android-actions/setup-android).

RuntimeVersion **9**, signed schema **1**, lifecycle schema **4** unchanged.
No Android/compiler capabilities changed. Integrated evidence is in
`../validation/CI_PUBLISHING_ACCEPTANCE.md`.
