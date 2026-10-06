> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Phase 2B — MIT-core xprem backend

2026-09-26: **Phase 2B PASS, host-side.**
Phase 2C device results are recorded in [PHASE2C_DEVICE_PROOF.md](PHASE2C_DEVICE_PROOF.md).
The Phase 1 APK, compiler and runtime sources were not changed or exercised.

## Foundation and separation

Upstream: https://github.com/mercuretechnologies/xprem at
`b46e13569f5734a66ed903f75f51b87b78e80c1b`.
Original checkout: `~/.dootah-v2/xprem`, detached and clean.
Final build tree: `~/.dootah-v2/xprem-mit-final`.
Local paths in this record are relative to the operator's home directory; commands
that mount `$PWD` run from the repository root.
Toolchain: Go **1.26.8 linux/arm64**, `CGO_ENABLED=0`, no custom build tags,
`GOFLAGS=-mod=readonly`. The Docker toolchain is pinned by digest in `build.sh`.

[prepare.sh](../../v2/server/prepare.sh) exports only committed `config`, `internal`,
`go.mod`, `go.sum` and upstream license. It excludes all `ee/` directories and the
whole mixed-license router. It adds a 128-line
[composition entrypoint](../../v2/server/main.go) under `cmd/dootah`.
All **341 retained upstream files are byte-identical** to the clean pinned checkout.
No upstream subsystem was patched or replaced. No enterprise source was copied,
renamed, inspected for reimplementation, or unlocked.

The entrypoint wires existing PostgreSQL repositories, bucket/DB migrations,
app/key provisioning, branch/channel services, deployment, manifest and rollback
handlers. It retains xprem app-scoped API-key validation, credential context,
signed upload tokens, upload hashes and finalization checks. Keys authorize the
whole app; enterprise branch/action restrictions are not implemented. There is
no HTTP administration surface: initial provisioning is a local operator command.
Rollout selection remains in upstream services and is tested; rollout management
endpoints, dashboard, Observe, ClickHouse, jobs and diff generation are not exposed.

Audit: `go list -deps -json` enumerated **834 packages / 4,035 source/embedded-file
entries / 94 versioned modules**. Production and selected test dependency graphs
contain **zero `ee/` or upstream router packages**. `go mod graph`, `go mod verify`,
`go version -m` and source comparison were recorded outside Git. Generated SQL/Go
and embedded migrations remain upstream files; no EE build tags or imports remain
in the prepared tree. The executable is built only from this tree. No upstream
Dockerfile, dashboard, published image or full checkout is packaged.

## License result and notices

“MIT-only” means **xprem's reusable community source with no commercial `ee/`
source**, not that every third-party dependency has an MIT license. The retained
bsdiff component has its own BSD-2-Clause notice. Dependency licenses include
MIT, MIT-0, BSD-2/3-Clause, Apache-2.0 and MPL-2.0. These are OSS dependencies,
not enterprise xprem entitlements.

`github.com/google/go-licenses/v2@v2.0.1 report ./cmd/dootah` was run on the final
tree. [The reviewed inventory](evidence/phase2b-20260926/licenses.csv) resolves its
nine unclassified Segment assembly packages to the module's
[MIT-0 license](https://github.com/segmentio/asm/blob/v1.2.1/LICENSE).
The scanner could not generate URLs for local module name `xprem`; these were
replaced by pinned source/license URLs. It warns that assembly dependencies cannot
be fully inspected; the retained arm64 assembly belongs to the listed modules
and uses Go assembler headers. CGO is disabled. This is an audit of the selected
Linux/arm64 build, not every possible target/tag or a vulnerability audit.

Five River module entries use MPL-2.0; their files are unmodified. Distribution
must preserve notices and meet covered-source availability obligations. No binary
is being publicly distributed here. Keep Go's BSD notice and all dependency
notices when packaging a distributable runtime image. The original license is in
the prepared source; [XPREM-LICENSE.md](../../v2/server/XPREM-LICENSE.md) accompanies
the Dootah composition. No license checks were bypassed.

## Build and tests

From the repository root, with a clean xprem checkout at the pinned commit and a
**new** output directory:

```sh
bash v2/server/prepare.sh /path/to/xprem-checkout /absolute/new/build-directory
bash v2/server/build.sh /absolute/new/build-directory /path/to/xprem-checkout
node --test v2/server/audit.test.mjs
```

`build.sh` requires the upstream checkout and always runs `audit.py` against it, writing
`audit.json` into the build directory. The audit fails unless the upstream HEAD is the
pinned commit, the checkout is clean, and every exported `config/` and `internal/` file is
byte-identical to it.

The script refuses a wrong upstream HEAD or existing output directory, exports the
pinned commit rather than worktree modifications, keeps module locks unchanged,
builds with `-trimpath`, and runs:

```sh
go test -json ./internal/services ./internal/handlers ./internal/crypto ./internal/rollout ./internal/bucket/...
```

Result: **345 passing test/subtest results**, zero failures, four Azurite integration
tests skipped because Azure is outside this local-storage proof. Tests cover
manifest/selection, signing, rollback/republish, rollout bucketing/resolution,
upload validation and storage. Local PostgreSQL behavior is additionally exercised
through the running server; no destructive upstream DB tests were pointed at the
acceptance database. No unrelated V1 compiler matrix was rerun.

## PostgreSQL, storage and local operator workflow

Used the existing `dootah-v2-postgres`, PostgreSQL 17, `dootah_v2` database and
`dootah` user. Mac endpoint remains `127.0.0.1:54329`; container endpoint is
`dootah-v2-postgres:5432`. Credentials remain outside Git in
`~/.dootah-v2/server-state/server.env` (mode 0600).
No PostgreSQL install or replacement occurred.

On an empty local state directory, `DB_URL` must hold the existing container DSN:

```sh
node v2/server/local-env.mjs /absolute/new/private-state
```

This generates a master key, JWT secret and migration-admin credentials once and
refuses to overwrite the environment file. Preserve that file with database
backups: regenerating the master key makes stored app signing keys unreadable.
For the completed proof **reuse the existing state**, do not provision again.

The server calls upstream `migrations.SetEngine`, `postgres.RunDBMigrations` and
`bucketmigration.EnsureMigrations`. PostgreSQL reached **20260913120000**, with
46 applied history records including the initial record (45 migrations).
Tables include apps, branches, channels, updates, blobs and API keys. Upstream MIT
migrations also create unused enterprise-related tables: table names do not imply
EE source was linked. Migrations were preserved rather than redesigned.

Local assets: `~/.dootah-v2/server-state/assets`, mounted at
`/state/assets`, with `STORAGE_MODE=local` and `LOCAL_BUCKET_BASE_PATH=/state/assets`.
xprem owns CAS paths, hash validation, metadata, upload completion and rollback.
No asset blobs, database files or private configuration are in Git.

For initial provisioning only, use the built executable in the pinned Go container
with the existing network, private env file and mounts shown below, adding
`-provision`. Redirect stdout to a mode-0600 file. Bucket migrations can log on
stdout; its **last line** is the credential JSON. Save it as `credentials.json`.
It contains appId, branch, channel, API key and public signing certificate.
Provisioning is intentionally one-shot, not an idempotent migration; repeating it
creates a new app. The existing proof has exactly one app, branch and channel.

Exact normal-network server launch (use a fresh container name):

```sh
docker run -d --name dootah-v2-server-next --network dootah-v2-net \
  --env-file $HOME/.dootah-v2/server-state/server.env \
  -p 127.0.0.1:3100:3100 \
  -v $HOME/.dootah-v2/server-state:/state \
  -v $HOME/.dootah-v2/xprem-mit-final/dootah-server:/dootah-server:ro \
  golang@sha256:8ac98ca534ac3f51e1f420a1dd2c15e74c75cfa0f23f3ad27eb5d7236c349a0c /dootah-server
```

The Go image is a local execution tool, not a proposed production server image.
`/ready` becomes available only after migrations; it is startup readiness, not a
continuous database health probe. The server is loopback-published, single operator,
plain HTTP for this local proof; production TLS, authorization and hardening remain.

## Publish, selection, signing and rollback evidence

The 70-line [publish adapter](../../v2/server/publish.mjs) reads an existing Android
Metro export and calls xprem request-upload, local upload and finalize APIs. It
does not compile Kotlin, generate JS, sign manifests or implement release storage.
It checks artifact path containment and returned upload origin/hash/path.

```sh
node v2/server/publish.mjs http://127.0.0.1:3100 \
  $HOME/.dootah-v2/server-state/credentials.json \
  $HOME/.dootah-v2/expo-custom-server/expo-updates-server/updates/dootah-v2-spike-1/1790433181487
```

Already performed; a duplicate publish may be refused by upstream deduplication.
Runtime `dootah-v2-spike-1`, Android, branch/channel `development`, app
`34e81d97-661f-4f3b-ad2c-8d4e7a6546a6`.
Initial update `17904436192742`, UUID `97de1526-320d-47fc-9407-b2f30bd4b187`.
Hermes bytes: **1,433,590**, SHA-256 base64url
`XFvqGrkn6WAwNw8VBH9NTTnIYeBhia_a3Brhz9l2FtI`, identical to Phase 1.

[Host assertions](../../v2/server/host-proof.mjs) verified app, runtime, platform and
channel filtering; the response identifies its selected branch. An unapproved
branch override cannot select another branch. Wrong-app assets return 404;
unauthenticated publishing/rollback returns 401. A deliberately corrupted upload
returns 400, finalization returns 400, and the active manifest remains unchanged.
The failed candidate remains an unpublished record, not another active release.

xprem generates a per-app RSA key pair. Its existing AES-GCM storage seals keys in
PostgreSQL using the external master key. The server signs the exact manifest and
rollback directive bytes; the publisher never receives the private signing key.
Clients must pin the provisioned public certificate, verify the signature, then
verify downloaded asset hashes. Host tests use Node's standard RSA verifier and
reject modified body/signature/asset bytes. This proves host cryptography, not
Android certificate configuration. A trusted publisher can submit intentional
code changes; hashes are not a sandbox or proof of code safety. A compromised
signing server can authorize updates. Dootah owns its publisher credentials,
server, keys, metadata and assets.

Restart preserved the complete manifest, selected UUID, signature validity and
asset bytes. Upstream rollback created a newer **signed `rollBackToEmbedded`**
directive. The previous CAS asset remained intact. A second restart preserved
the rollback directive. No client database mutation was used.

Final confirmation ran on Docker **internal** network `dootah-v2-proof-net`, with
the existing database additionally connected to it. An external IPv4 request
failed with `Network unreachable`. Because Docker Desktop did not expose its host
port on that network, the Node verifier shared the server's network namespace:

```sh
docker run --rm --network container:dootah-v2-server-final \
  -v $HOME/.dootah-v2/server-state:/state \
  -v $PWD/v2/server:/proof:ro \
  node@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 \
  node /proof/host-proof.mjs http://127.0.0.1:3100 /state/credentials.json /state/new-receipt.json before
```

For a new proof, run `before`, restart server, run `after` with the same receipt
path, run `rollback`, restart, then `rollback-check`. Receipt writes refuse
overwrite. The existing final server is stopped with rollback active; prepare a
new release with upstream republish before expecting a normal manifest again.
Final republish `17904440017102`; rollback `17904440213362` at
`2026-09-26T17:33:41.336Z`. No EAS account/service/API was needed. Optional Expo
provider/import code still exists in upstream packages, but this DB/local
composition never wires Expo import, uses no Expo token and worked without egress.

Durable [receipt](evidence/phase2b-20260926/receipt.json) includes module versions,
hashes, test counts, manifest IDs/metadata, rejection results and rollback receipts.
Raw dependency graphs, tests and license scanner logs remain in the external final
build directory; private credentials and raw host receipts remain in server-state.

## Limits and exact Phase 2C starting point

This proves a usable OSS backend composition, not SaaS readiness. No tenant
authorization, billing, analytics, rollout management UI, pause/kill or production
deployment was built. Freeze the upstream revision and rerun the graph/license
audit on upgrades. All publishers for an app currently have full release authority.
Graceful process draining, request-size/rate limits and a minimal production image
remain hardening work. The upstream migration-created admin account is unused.

At the end of Phase 2B both proof server containers were stopped; PostgreSQL remained healthy. The extra
internal proof network remains available. No device commands were run.

**First Phase 2C action:** start the proven final binary on `dootah-v2-net` using
the command above, then inspect the sample's installed update URL/header/certificate
configuration. Phase 1 used unsigned `/api/manifest` without xprem app/channel
headers; Phase 2C must establish the signed endpoint and certificate configuration
before beginning a fresh install-once device proof. Reuse the runtime/health adapter
and existing Hermes artifact. Do not claim its old APK already verifies signatures.


## Phase 2C continuation

The existing binary/state was reused as `dootah-v2-server-phase2c` on
`dootah-v2-net`. The sample now pins the provisioned public certificate and supplies
xprem app/channel identity. The initial fixture-to-xprem APK replacement exposed
stale embedded manifest generation; an approved corrected baseline established a
fresh embedded ID without clearing app data. See the device proof for the exact
APK, signing, two OTA releases, restarts, rollback and failure checks. No Phase 2B
reprovisioning or new backend implementation was performed. Phase 3 remains unstarted.
