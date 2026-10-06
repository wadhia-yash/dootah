# Self-hosted alpha deployment

This is a single-VM Docker Compose deployment. Local acceptance ran Linux/ARM64
and emulated Linux/AMD64 containers on Docker Desktop; no native Ubuntu VM or external
deployment has been qualified. See the [CI record](../../docs/v2/PHASE9E_CI.md). It does not deploy anything, create
accounts or request certificates until **you** run the production startup commands.
Runtime ABI 2, Logic ABI 1, SDK `0.1.0-alpha.1`, the BasicText renderer and installed
trust/fallback semantics are unchanged. No public registry publication is required.

## Supported shape and prerequisites

Use Ubuntu Server 24.04 LTS, ARM64 or AMD64, Docker Engine with the Compose plugin,
Git, Python 3, Bash and curl. Build requires Internet access to the pinned upstream
checkout, Go modules, Docker base images and the checksum-verified JSON JAR. Runtime
Cloud, xprem and PostgreSQL have no external Docker network; only Caddy does.

Planning minimum for a small controlled alpha: **2 vCPU, 4 GiB RAM, 30 GiB SSD** with
images built elsewhere or additional temporary build space. Recommended: **2–4 vCPU,
8 GiB RAM, 60 GiB SSD**, with at least 15 GiB free for building/upgrading. These are
capacity estimates, not load-tested throughput guarantees. Node, on-demand Java 21
validators, Go xprem, PostgreSQL 17 and Caddy share the VM. Storage grows with retained
updates and telemetry. Monitor memory/disk; keep encrypted off-host backups. The local
acceptance Docker engine had approximately 4 GiB available. There is no HA, clustering,
PITR, automatic retention policy, zero-downtime upgrade or managed alerting claim.

```text
Internet -> TCP 80/443 -> Caddy -> internal ingress -> Cloud
                                                     |
                                              internal backend
                                               /           \
                                            xprem       PostgreSQL 17
                                          state volume   data volume
```

Only Caddy publishes ports. Cloud is the only application exposed by its routes.
No route proxies directly to xprem. Cloud/xprem/Postgres have **no host port mappings**.
Caddy cannot join the backend network. Docker administrators/root are trusted operators;
do not give customers host shell or Docker access. Compose internal networking and
secrets follow the [Docker networking/service model](https://docs.docker.com/reference/compose-file/services/)
and [file-secret model](https://docs.docker.com/compose/how-tos/use-secrets/).

### Client identity behind Caddy

Cloud's per-client request limits key on the client address. Behind Caddy, the TCP
peer is always Caddy, so Cloud uses an explicit trusted-proxy model:

- Compose declares the internal `ingress` network as `<prefix>.0/28` and pins Caddy at
  `<prefix>.14`. Cloud's dynamic address comes from `<prefix>.0/29`. The prefix
  defaults to `10.231.47`. If that range collides with a host or VPN route, set
  `DOOTAH_INGRESS_PREFIX` (for example `10.231.48`) in the deployment env file.
- Cloud sets `DOOTAH_TRUSTED_PROXIES` to Caddy's address only. It accepts a
  comma-separated list of addresses or CIDR networks. Malformed values, networks with
  host bits set and `/0` stop startup. Absent or blank means nothing is trusted.
- Every Caddyfile sets `X-Forwarded-For` to Caddy's own `{client_ip}`, replacing any
  client-sent value. Do not add Caddy `trusted_proxies` unless a real CDN or load
  balancer sits in front of Caddy and only its ranges are listed.
- Cloud reads `X-Forwarded-For` only when the TCP peer is a trusted proxy. It takes the
  rightmost entry that is not itself trusted. A missing or malformed value falls back
  to the peer. `Forwarded` and `X-Real-IP` are never read.
- Requests from any other peer use the socket address and ignore forwarding headers.
  IPv4-mapped IPv6 is treated as IPv4, and IPv6 clients are keyed by their `/64`.

This restores per-client isolation for application-layer limits. It is not DDoS
protection.

Per-client attribution depends on Docker delivering the real client address to Caddy.
Hosted acceptance on Linux verified that real Caddy keeps distinct clients separate and
replaces forged headers, with clients connecting over a Docker network rather than a
published host port. On the supported shape, rootful Docker Engine on Linux publishing
ports through its iptables NAT, Docker preserves the connecting address. Docker
Desktop, rootless Docker, Docker's userland proxy, host port-forwarding layers, tunnels
and similar networking can present every client as one address. All clients then share
one rate-limit bucket. That reduces fairness and availability under load; it is not an
authentication bypass and does not let a client choose another client's identity.
Per-client limits are not validated on those setups. The ngrok overlay remains
alpha-validation transport only.

## USER ACTION REQUIRED — DEPLOYMENT

Nothing below has been run against external infrastructure.

1. Obtain the recommended server and persistent SSD above; install Ubuntu 24.04 LTS
   and Docker Engine/Compose. Allow **TCP 80 and 443** publicly. Restrict SSH TCP 22
   to your administration IP/VPN. No UDP port is required by this Compose file.
   Check the provider firewall as well as host rules: Docker port publishing needs
   explicit consideration when configuring a host firewall.
2. Choose **any domain you control**, e.g. `updates.example.com`. Add an **A** record
   pointing to the VM IPv4. Add **AAAA only if IPv6 is configured and tested**. Do not
   create a record for xprem/Postgres. No particular Dootah-owned domain is assumed.
3. Create the checkout and operator directories. Run the following in a private
   administrative shell. Set the repo remote to the existing repository you own;
   no public upload or registry push is needed.

   ```sh
   sudo install -d -m 0700 /srv/dootah/operator /srv/dootah/backups
   sudo install -d -m 0755 /srv/dootah/repo
   # Place/clone this repository into /srv/dootah/repo, checkout a reviewed V2 commit.
   cd /srv/dootah/repo
   git status --short
   git rev-parse HEAD
   sudo cp v2/deploy/.env.example /srv/dootah/operator/deploy.env
   sudo chmod 0600 /srv/dootah/operator/deploy.env
   sudoedit /srv/dootah/operator/deploy.env
   ```

   Set `DOOTAH_SITE` to your domain, `DOOTAH_CLOUD_ORIGIN` to its exact `https://`
   origin (no path/trailing slash), and `DOOTAH_OPERATOR_EMAIL` to the initial user.
   Keep the example `/srv/dootah` paths if following these commands. Production uses
   `Caddyfile`, **not `Caddyfile.local`**. Leave ports 80/443 and bind IP 0.0.0.0.
   The env file contains configuration/paths only, never passwords or API keys.
4. Generate secrets once. This creates every required password, database URL, xprem
   encryption/upload key and an initially empty bindings file, all mode 0600.

   ```sh
   sudo python3 v2/deploy/ops.py secrets /srv/dootah/operator/secrets
   ```

   See [the secret inventory](secrets/README.md). Preserve the master key with the
   xprem DB. Cloud sessions use random opaque credentials hashed in PostgreSQL;
   there is no missing static Cloud session signing secret to invent. Customer
   backend enrollment tokens stay in that customer's secret store, not this image.
5. Obtain the clean pinned upstream checkout **outside** the Dootah checkout and build:

   ```sh
   sudo git clone https://github.com/mercuretechnologies/xprem /srv/dootah/xprem-upstream
   sudo git -C /srv/dootah/xprem-upstream checkout --detach b46e13569f5734a66ed903f75f51b87b78e80c1b
   sudo bash v2/deploy/build.sh /srv/dootah/xprem-upstream
   ```

   `prepare.sh` exports only the pinned allowed community inputs, excluding `ee/`
   and the mixed-license router. The existing byte-comparison/dependency audit runs
   before image build. No full upstream checkout enters a Docker context. Builds
   never mount operator secrets. Optional `DOOTAH_BUILD_EVIDENCE=/private/path`
   retains audit/test JSON outside Git. Four upstream Azure integration tests are
   explicitly outside the selected local-storage deployment and reported skipped;
   they are not claimed as exercised. No Azure service is wired into this stack.
6. Define local command helpers, bootstrap the cluster and run migrations explicitly:

   ```sh
   dc() { sudo docker compose --env-file /srv/dootah/operator/deploy.env -f /srv/dootah/repo/v2/deploy/compose.yaml "$@"; }
   op() { sudo python3 /srv/dootah/repo/v2/deploy/ops.py --env /srv/dootah/operator/deploy.env "$@"; }
   dc up -d postgres
   dc exec -T postgres pg_isready -U postgres -d postgres
   op bootstrap
   dc run --rm cloud-migrate
   dc run --rm xprem-migrate
   op grant-xprem
   dc run --rm cloud-user
   ```

   Bootstrap creates separate databases and owner/runtime logins. It is safe to rerun
   with the **same** secret set; it does not overwrite existing passwords. Cloud's
   runtime role inherits only the existing `dootah_cloud_runtime` grants. xprem's
   runtime role has DML/sequence rights within its isolated DB, without schema/role
   ownership or migration-table writes. It necessarily reads its encrypted app key
   store to sign. HTTP startup does not migrate. Run `grant-xprem` after each xprem
   migration to grant new table rights. User provisioning is deliberately create-only:
   a duplicate email fails rather than resetting credentials. The xprem upstream
   seeded admin is unused by this composition; its dashboard/admin HTTP routes are absent.
7. Start xprem and Cloud, then the proxy. **This production proxy startup is the step
   that requests public certificates**, after DNS/firewall verification:

   ```sh
   dc up -d xprem cloud
   dc ps
   dc up -d proxy
   curl --fail https://updates.example.com/live
   curl --fail https://updates.example.com/ready
   ```

   Replace the domain in curl. Do not use `-k`. Caddy automatically manages certificates
   for the chosen domain; its named `caddy-data` volume preserves certificate/account
   state. Caddy uses request-body/header limits and read/write timeouts, HSTS and
   `nosniff`; no access log records cookies, authorization headers or upload tokens.
   See [Caddy TLS](https://caddyserver.com/docs/caddyfile/directives/tls) and
   [request limits](https://caddyserver.com/docs/caddyfile/directives/request_body).
8. Use the [customer quickstart](../../docs/getting-started/quickstart.md) to log in with the initial
   user, create the org/app, and capture its Cloud app UUID. The password file stays
   private; transfer credentials only through your private administrative channel.
   Customer org/app/environment/release state is created through authenticated APIs.
   No customer DB editing is part of deployment.
9. Provision that application's private xprem identity and signing certificate:

   ```sh
   sudo sh -c 'umask 077; docker compose --env-file /srv/dootah/operator/deploy.env -f /srv/dootah/repo/v2/deploy/compose.yaml run --rm --no-deps -T xprem -provision -app-name "My Application" -channel production > /srv/dootah/operator/my-app.credentials.json'
   # Replace CLOUD_APP_UUID with the existing Cloud application's UUID:
   op binding-put CLOUD_APP_UUID /srv/dootah/operator/my-app.credentials.json
   op bindings-list
   ```

   Provisioning is one-shot; repeating it creates another identity. App name also
   determines the certificate common name via unchanged upstream code. Distribute
   only the public certificate to the native build; keep API keys private. The channel
   defaults to `development` if omitted; select your native/Cloud channel deliberately.
   After the native build, follow quickstart retention/import/contract registration and publish.
10. Verify TLS, `/live`, `/ready`, private ports and persistence. Send back **only**:
    accepted repo SHA and image IDs, `dc ps`, readiness status, TLS hostname/expiry,
    firewall/port summary, sanitized CLI operation IDs/results (including
    `signatureVerified: true` for rollback), and backup/restore result. Never send
    passwords, URLs containing credentials, raw `docker inspect`, bindings, private
    keys, cookies, tokens, enrollment tickets or database dumps.

## Configuration and health

The image build always creates its context under `umask 077`. Each image establishes
root-owned immutable payload permissions itself: readable files, traversable
directories, and executable entrypoints. Runtime services still drop to UID/GID
10001. xprem storage and secret snapshot directories are mode 0700; secret files
remain 0400/0600 on input and 0600 in the runtime snapshot. No secret mount is
included in immutable-payload permission normalization.
The source-mount directory `/run/input` is root-owned 0700, preventing unrelated
container UIDs from traversing it even on host-sharing filesystems. The root
entrypoint reads these inputs once and then drops privileges.

`build.sh` ends with an offline image permission/access audit, including the packaged
validator and rejection of insecure secret inputs. To repeat it for built images:

```sh
python3 v2/deploy/image_permissions.py
```

The existing `python3 v2/deploy/acceptance.py` then exercises fresh migrations/admin
commands, non-root HTTP services, health, secret isolation, signed operations and
restore using those images. Together these checks regress private-umask builds
before a clean-room Android trial. Caddy configuration is mounted read-only into
the upstream proxy, whose configuration loader runs as root; it is not copied into
the Cloud/xprem payloads. Host operator scripts do not run inside those images.

Cloud image packages Node 24, Java 21 JRE, the shared unchanged `PortableProgram`
validator and its checksum-verified JSON dependency. No Gradle wrapper, developer path,
Android SDK or manually assembled classpath is needed at runtime. The image supplies
`DOOTAH_JAVA` and `DOOTAH_VALIDATOR_CLASSPATH`. Runtime builds do not use copied helpers.

`DOOTAH_MODE=production` and `NODE_ENV=production` are explicit in the Cloud image and
Compose. `DOOTAH_CLOUD_HOST=0.0.0.0` is container-internal. Missing/malformed DB URL,
non-HTTPS/non-origin URL, local HTTP bypass, invalid/unsafe binding configuration,
missing validator/JDK 21, missing credentials, wrong schema or overprivileged runtime
DB access prevent startup. Errors name the check without including input values.

Development retains implicit `development` mode for existing local callers, loopback
host and the explicit `DOOTAH_ALLOW_LOCAL_HTTP=true` loopback-only exception. Production
never permits that exception. Arbitrary external PostgreSQL/xprem endpoints are not a
supported alpha topology: the supplied internal names are intentional; do not publish
ports to make them accessible. The application has configurable endpoints for later
separation. The private xprem `BASE_URL` is the **public Cloud HTTPS origin**, while
bindings' `base` is **http://xprem:3100**. This preserves signed asset URLs through the
gateway. Never substitute the private origin for the advertised origin.

`/live` means process alive. Cloud `/ready` checks schema version, runtime privileges,
binding configuration and app identities, JDK/validator executability. Successful
checks are cached for five seconds and concurrent probes share one check. It does not
contact every xprem app: one bad API key/customer delivery failure does not mark all
Cloud unavailable. Operations retain their existing retry/status visibility. xprem
`/ready` checks PostgreSQL migration version and storage migration history. PostgreSQL
uses `pg_isready`. Cloud and xprem images carry health checks; Compose applies restart
policies and startup dependencies. An unhealthy status by itself does not restart a
running container: investigate, repair and restart explicitly. Cloud DB reconnects on
PostgreSQL restart. No automatic migration takes place in either production HTTP process.

Logs use composition-owned structured lifecycle events. Production xprem suppresses
raw upstream logger messages, which may include request-derived data. Inspect operation
IDs/status and health for troubleshooting, rather than enabling raw upstream logging
with live credentials. The controlled alpha retains Cloud's existing login/account and
credential request limits (20 logins and 600 anonymous update requests per client per
minute). The client is attributed through Caddy as described in
[Client identity behind Caddy](#client-identity-behind-caddy). Under the ngrok overlay,
Caddy's peer is the host port forward, so tunnel clients still share one bucket. These
are application-layer limits only. Distributed edge DDoS protection is not implemented.
Do not claim Internet-scale abuse resistance.

## Bindings and API-key rotation

The smallest alpha operator model is an OS-authenticated, mode-0600 config file, plus
`ops.py` validation. It is not a new customer/admin HTTP API. `binding-put` validates
Cloud UUID existence through read-only runtime queries, exact private/advertised
origins, exclusive upstream app identity, API-key shape and an RSA self-signed
certificate. Before replacement it authenticates against the private xprem
`cloudBinding` route and checks the actual app certificate; a bad key is rejected. It atomically replaces the file and recreates Cloud, so restart has a
short availability gap. In-flight durable operations reconcile through existing logic.
A failed candidate leaves the old file intact. Back up before rotation.

Replacement must preserve xprem app ID and exact certificate: changing an installed
signing identity is **not** API-key rotation and is rejected. For an existing app:

```sh
# Redirect all credential output directly into a new private operator file.
sudo sh -c 'umask 077; docker compose --env-file /srv/dootah/operator/deploy.env -f /srv/dootah/repo/v2/deploy/compose.yaml run --rm --no-deps -T xprem -key-app XPREM_APP_UUID -key-action create > /srv/dootah/operator/rotated.credentials.json'
op binding-put CLOUD_APP_UUID /srv/dootah/operator/rotated.credentials.json
op bindings-list
# Verify a signed operation, then identify and revoke the OLD key:
dc run --rm --no-deps -T xprem -key-app XPREM_APP_UUID -key-action list
dc run --rm --no-deps -T xprem -key-app XPREM_APP_UUID -key-action revoke -key-id OLD_KEY_ID
```

Keep the old key until the new binding is proven. `list` returns upstream metadata/hints,
not full key values. Revoke using the old numeric ID; do not revoke the newly created key.
If rotation fails before revocation, restore the previous binding file and recreate Cloud.
An operator changing raw JSON should run the same validation path before restarting.
An empty `{}` is valid only to bootstrap a server with no provisioned deliveries.

## Backups, restore and upgrades

Back up before migrations, image changes and key/binding rotation:

```sh
op backup /srv/dootah/backups/2026-10-03-before-upgrade
```

Use a fresh destination name each time. This stops proxy/Cloud/xprem writers, leaves
PostgreSQL running, takes `pg_dump -Fc` of **both** databases, archives xprem's entire
state volume, copies all secret files, bindings, env/Compose/Caddy configuration and
records image IDs, then starts services again. A failed backup leaves writers stopped
for inspection. This is a maintenance-window snapshot, not PITR or a live distributed
snapshot. Scheduling, encryption, off-host transfer and retention are the operator's
responsibility; this script does not contact external backup storage.

**Non-regeneratable:** xprem DB (encrypted private signing keys/app identities), matching
master key, update blobs/storage and Cloud DB (tenant state, contracts, policy, grants,
audit, credential digests). Losing signing keys/master key can require new APK trust
configuration; there is no recovery by inventing replacement keys. Include private
binding API keys and operator config in the protected set. API keys and DB passwords
can be explicitly rotated if the underlying identities/master key survive.

**Regeneratable:** container images from retained commit/pinned dependencies, validator,
Go binary, caches and temporary validation files. Caddy certificates can be reissued
subject to CA rate limits; its volumes persist across routine restarts but are not part
of this application backup. Preserve those volumes separately if needed. Never treat
`docker compose down -v` as a routine stop; it deletes persistent volumes.

Restore to a **new Compose project/empty volumes**, keeping ingress closed:

1. Recover the accepted code/images and entire backup set. Restore `secrets/` into
   a mode-0700 external directory; keep each file mode 0600. Restore/copy `deploy.env`
   and set its secret/Caddy paths. Set a fresh `COMPOSE_PROJECT_NAME`, preserving the
   public origin for installed clients. Do not generate new master/signing keys.
2. Stop the old deployment before reusing its public ports/origin.
3. Define `dc`/`op` for the recovered env file, then run:

   ```sh
   dc up -d postgres
   op bootstrap
   op restore /srv/dootah/backups/2026-10-03-before-upgrade
   dc up -d xprem cloud
   dc ps
   dc up -d proxy
   curl --fail https://updates.example.com/ready
   ```

   Restore refuses databases with existing application tables or nonempty xprem state.
   It restores DB ownership to the migration owners and reapplies runtime grants, then
   restores storage. Start only after DB + matching master key + state + bindings are
   present. Check retained apps/environments, signed manifests/assets and rollback.
   Run migrations only if intentionally restoring into a newer compatible version.

Upgrade sequence: backup → pull/build reviewed images → stop proxy/Cloud/xprem → run
`cloud-migrate`, `xprem-migrate`, `grant-xprem` explicitly → recreate services → readiness
and signed-flow verification → reopen ingress by starting the proxy last. Retain
previous image IDs/tags and the backup. This is not zero downtime. Rolling back images
is safe only if the new schema is backward compatible; otherwise restore the complete
prior backup into fresh volumes. Never roll back just the xprem DB while retaining
mismatched storage/keys.

Deployments created before the pinned ingress address must recreate the `ingress`
network once, inside an upgrade window. Do not use a bare `dc up -d` for this: it starts
Caddy together with everything else, before Cloud and xprem have been checked.

```sh
dc down                    # never -v: named volumes and their data are retained
dc up -d postgres xprem cloud
dc ps                      # wait until postgres, xprem and cloud report healthy
dc up -d proxy             # expose Caddy last
curl --fail https://updates.example.com/ready
```

`dc down` without `-v` removes containers and networks only. The database, xprem state
and Caddy certificate volumes are kept. `dc up -d postgres xprem cloud` recreates the
internal networks without starting the proxy. If the same window also upgrades images,
run the migration and `grant-xprem` steps after `postgres` is healthy and before
starting `xprem` and `cloud`. Service is unavailable from `dc down` until the proxy
starts.

## Persistent private HTTPS for a physical Android device

**Not the current clean-room acceptance route:** the documented IP endpoint failed
live TLS negotiation in the second Phase 9G attempt. Configuration validation alone
did not qualify it. Use [ALPHA / CLEAN-ROOM VALIDATION USING NGROK](../../docs/self-hosting/ngrok.md)
for the authorized tunnel topology; the public-domain production walkthrough above
remains separate. The two failed attempts remain historical evidence.

Use this alternative to the public-domain deployment walkthrough for a controlled
alpha trial on a Docker/Compose development host (including Docker Desktop).
Cloud and xprem still run in production mode on the existing private networks.
Only Caddy publishes host ports, bound to loopback. No public DNS, ACME request,
host/device trust-store installation or external infrastructure is needed.
This proves private HTTPS only, not Internet deployment or public TLS.

### Keep the two trust systems separate

| Certificate | Created by / purpose | Where the public certificate goes |
| --- | --- | --- |
| HTTPS transport root CA (`https-ca.pem`) | Caddy internal PKI; verifies the HTTPS server certificate and endpoint identity | curl `--cacert`, Node `NODE_EXTRA_CA_CERTS`, Android debug network security resource |
| OTA update-signing certificate (`update-certificate.pem`) | Per-app xprem provisioning; authenticates signed update manifests and rollback directives | Android `dootah.publicCertificate` |

Neither certificate substitutes for the other. Keep both private keys inside their
operator-controlled state; never export them to clients. A successful HTTPS check
does not prove an OTA signature was accepted. A signed update does not establish
HTTPS server trust. Retain both server-side identities through the one-install trial.

### Create isolated persistent state and build the services

Prerequisites are the quickstart's Android/JDK/Node tools plus Docker/Compose, Git,
Python 3, Bash, curl, OpenSSL and Android platform-tools (`adb`). Use a dedicated
shell. These commands assume the reviewed Dootah checkout is the current directory;
use absolute paths without whitespace for the operator env file values.

```sh
export DOOTAH_REPO="$PWD"
umask 077
export DOOTAH_WORK="$(mktemp -d "${TMPDIR:-/tmp}/dootah-private.XXXXXXXX")"
export COMPOSE_PROJECT_NAME="$(basename "$DOOTAH_WORK" | tr '[:upper:]' '[:lower:]' | tr '.' '-')"
mkdir -p "$DOOTAH_WORK/operator" "$DOOTAH_WORK/evidence"
export GRADLE_USER_HOME="$DOOTAH_WORK/gradle-producer"
export npm_config_cache="$DOOTAH_WORK/npm-cache"
mkdir -p "$GRADLE_USER_HOME" "$npm_config_cache"
export DOOTAH_PRIVATE="$DOOTAH_WORK/customer"
mkdir -p "$DOOTAH_PRIVATE/publisher" "$DOOTAH_PRIVATE/native-release"
export DOOTAH_CLOUD_URL='https://127.0.0.1:8443'
export DOOTAH_OPERATOR_EMAIL='operator@example.test'
cat > "$DOOTAH_WORK/operator/deploy.env" <<EOF
COMPOSE_PROJECT_NAME=$COMPOSE_PROJECT_NAME
DOOTAH_SITE=https://127.0.0.1
DOOTAH_CLOUD_ORIGIN=$DOOTAH_CLOUD_URL
DOOTAH_OPERATOR_EMAIL=$DOOTAH_OPERATOR_EMAIL
DOOTAH_SECRETS_DIR=$DOOTAH_WORK/operator/secrets
DOOTAH_CADDYFILE=$DOOTAH_REPO/v2/deploy/Caddyfile.local
DOOTAH_BIND_IP=127.0.0.1
DOOTAH_HTTP_PORT=8080
DOOTAH_HTTPS_PORT=8443
EOF
python3 "$DOOTAH_REPO/v2/deploy/ops.py" secrets "$DOOTAH_WORK/operator/secrets"
dc() { docker compose --env-file "$DOOTAH_WORK/operator/deploy.env" -f "$DOOTAH_REPO/v2/deploy/compose.yaml" "$@"; }
op() { python3 "$DOOTAH_REPO/v2/deploy/ops.py" --env "$DOOTAH_WORK/operator/deploy.env" "$@"; }
dc config --quiet
git clone https://github.com/mercuretechnologies/xprem "$DOOTAH_WORK/xprem-upstream"
git -C "$DOOTAH_WORK/xprem-upstream" checkout --detach b46e13569f5734a66ed903f75f51b87b78e80c1b
export DOOTAH_GO_MOD_VOLUME="$COMPOSE_PROJECT_NAME-go-mod"
export DOOTAH_GO_BUILD_VOLUME="$COMPOSE_PROJECT_NAME-go-build"
export DOOTAH_BUILD_EVIDENCE="$DOOTAH_WORK/evidence/server-build"
bash "$DOOTAH_REPO/v2/deploy/build.sh" "$DOOTAH_WORK/xprem-upstream"
```

Select unused host ports before startup; if replacing 8443, change the Cloud origin,
Compose HTTPS port and every client/adb port below together. Caddy listens on 443
inside its container; the advertised Cloud origin uses the host/device port 8443.
`Caddyfile.local` already selects `tls internal` and issues a certificate for
`127.0.0.1`. Its CA lives in this project's fresh `caddy-data` volume. Never reuse
another trial's project name, volumes, secrets, bindings or retained releases.

For clean-room source builds, use a new checkout of the reviewed commit (for example,
`git clone --no-local --no-hardlinks SOURCE_CHECKOUT NEW_CHECKOUT`, then
`git checkout --detach REVIEWED_SHA` inside it) before running this section. This
excludes ignored producer/build outputs. Stage the SDK into a new path under
`DOOTAH_WORK` using the quickstart. Before building the separate Android consumer,
set `GRADLE_USER_HOME="$DOOTAH_WORK/gradle-consumer"` and create that empty directory.
Resolve Dootah only from the new public-version stage in both repository scopes.

### Bootstrap and verify HTTPS

```sh
dc up -d postgres
dc exec -T postgres pg_isready -U postgres -d postgres
op bootstrap
dc run --rm cloud-migrate
dc run --rm xprem-migrate
op grant-xprem
dc run --rm cloud-user
dc up -d xprem cloud
dc up -d proxy
dc ps
export DOOTAH_HTTPS_CA="$DOOTAH_WORK/operator/https-ca.pem"
dc cp proxy:/data/caddy/pki/authorities/local/root.crt "$DOOTAH_HTTPS_CA"
openssl x509 -in "$DOOTAH_HTTPS_CA" -noout -subject -issuer -fingerprint -sha256
curl --fail --cacert "$DOOTAH_HTTPS_CA" "$DOOTAH_CLOUD_URL/live"
curl --fail --cacert "$DOOTAH_HTTPS_CA" "$DOOTAH_CLOUD_URL/ready"
```

Wait for PostgreSQL readiness before bootstrap. If a service is still starting,
repeat its readiness check; investigate an unhealthy service before proceeding.
Copy only `root.crt` from the authenticated local operator's container, not the
PKI directory or any key. Record its SHA-256 fingerprint and compare it when
transferring the public CA to another machine. `--cacert` enables validation with
this explicit CA; hostname/IP matching remains enabled. Do not bypass verification.

The supported customer CLI is a Node launcher; its HTTPS calls use Node's trust
configuration. Set the following **before starting each CLI/Node process** in this
dedicated shell. No Java trust-store modification is required for these HTTP calls.

```sh
export NODE_EXTRA_CA_CERTS="$DOOTAH_HTTPS_CA"
node --input-type=module -e 'const r = await fetch(process.env.DOOTAH_CLOUD_URL + "/ready"); if (!r.ok) process.exit(1); console.log(await r.text());'
```

Use the quickstart's documented CLI build and PATH setup, retaining the environment
above. Log in with the generated initial user's private password file:

```sh
dootah login --email "$DOOTAH_OPERATOR_EMAIL" --password-file "$DOOTAH_WORK/operator/secrets/initial-password" --session-file "$DOOTAH_PRIVATE/session"
dootah org create --name 'Private alpha organization' --session-file "$DOOTAH_PRIVATE/session"
export DOOTAH_ORGANIZATION='RETURNED_ORGANIZATION_ID'
dootah app create --name 'Private alpha Android app' --session-file "$DOOTAH_PRIVATE/session"
export DOOTAH_APP_ID='RETURNED_APP_ID'
dc run --rm --no-deps -T xprem -provision -app-name 'Private alpha Android app' -channel development > "$DOOTAH_WORK/operator/app.credentials.json"
op binding-put "$DOOTAH_APP_ID" "$DOOTAH_WORK/operator/app.credentials.json"
op bindings-list
curl --fail --cacert "$DOOTAH_HTTPS_CA" "$DOOTAH_CLOUD_URL/ready"
```

The credentials JSON is private and contains an API key; never print or commit it.
After creating your fresh Android project, set `DOOTAH_APP_ROOT` to its absolute
root and extract **only the OTA public signing certificate** into the quickstart's
normal plugin input:

```sh
python3 - "$DOOTAH_WORK/operator/app.credentials.json" "$DOOTAH_APP_ROOT/app/update-certificate.pem" <<'PY'
import json, pathlib, sys
credentials = json.loads(pathlib.Path(sys.argv[1]).read_text())
pathlib.Path(sys.argv[2]).write_text(credentials['certificate'])
PY
```

### Android trust and USB reachability before the one installation

Keep `dootah.updateUrl` as `https://127.0.0.1:8443/manifest`, channel `development`,
and `publicCertificate = file('update-certificate.pem')`. Do not set a cleartext
debug URL or enable the local HTTP exception.

Use Android's per-app, domain-scoped custom trust anchors. For the controlled
**debug** baseline, add these files before retention and installation. The debug
source set keeps this private CA configuration out of a normal release build.
Merge deliberately if your app already has a network security configuration.

```sh
mkdir -p "$DOOTAH_APP_ROOT/app/src/debug/res/raw" "$DOOTAH_APP_ROOT/app/src/debug/res/xml"
cp "$DOOTAH_HTTPS_CA" "$DOOTAH_APP_ROOT/app/src/debug/res/raw/dootah_https_ca.pem"
```

`app/src/debug/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/dootah_private_https" />
</manifest>
```

`app/src/debug/res/xml/dootah_private_https.xml`:

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <trust-anchors>
            <certificates src="@raw/dootah_https_ca" />
        </trust-anchors>
    </domain-config>
</network-security-config>
```

This scopes the private CA to this app's loopback HTTPS connections; it does not
install a CA into Android's global/user store or affect other apps. Android domain
rules are not port-scoped. Other hosts retain the base system trust policy and
cleartext remains disabled. The OTA certificate stays in the plugin input; do not
put it in `trust-anchors`. Never package Caddy's private keys.

Connect the physical device over USB, enable USB debugging, unlock it and accept
the host authorization prompt. Select its serial from `adb devices -l`:

```sh
adb devices -l
export ANDROID_SERIAL='YOUR_DEVICE_SERIAL'
adb reverse --list
adb reverse --no-rebind tcp:8443 tcp:8443
adb reverse --list
```

Choose another unused port before the build if a preexisting rule owns this port;
do not overwrite unrelated forwarding. Device `127.0.0.1:8443` now reaches the
host's loopback Caddy port through USB. TLS remains end-to-end between the app and
Caddy, including IP identity checks. No device DNS, LAN exposure or browser trust
exception is required. Reconnect this rule after a USB disconnect if necessary.

Return to the quickstart: build/retain, import/register, install the accepted APK
once, verify native baseline and enroll through its native ticket-entry UI. The
successful enrollment callback verifies an app HTTPS exchange using this Android
policy; curl/Node success alone does not prove Android trust. A browser may reject
the private CA because it does not use this app's trust configuration.
Resolve any Android TLS problem before accepting the one-install baseline; if
native configuration is wrong after that point, stop the attempt instead of
rebuilding/reinstalling or clearing data. Record APK SHA-256 and install timestamp.
Keep these fixed through Kotlin analysis, Cloud publication, OTA and rollback.

### Offline check and cleanup

To make only Dootah retrieval unavailable, remove this trial's reverse rule, then
normally restart the app and verify cached OTA. Restore the rule before requesting
and receiving rollback. Wi-Fi/mobile settings need not change:

```sh
adb reverse --remove tcp:8443
# Restart the app and verify the cached update with the same APK and app data.
adb reverse --no-rebind tcp:8443 tcp:8443
```

Finish the quickstart's customer CLI rollback, allow its online check and restart,
and verify native restoration before teardown. Preserve sanitized results, hashes
and stage `SHA256SUMS` outside the disposable service state. Do not include tokens,
credentials, raw logs, retained sources/APKs or private keys in Git.

For a temporary pause, `dc stop` preserves volumes; `dc up -d postgres xprem cloud
proxy` resumes the same identities. For final disposal of **this trial only**:

```sh
export DOOTAH_ANDROID_PACKAGE='YOUR_TEST_APPLICATION_ID'
adb shell am force-stop "$DOOTAH_ANDROID_PACKAGE"
adb reverse --remove tcp:8443
dc down --volumes --remove-orphans
docker volume rm "$DOOTAH_GO_MOD_VOLUME" "$DOOTAH_GO_BUILD_VOLUME"
unset NODE_EXTRA_CA_CERTS
```

Remove only reverse rules created by this trial. No system CA or radio settings
were changed. Volume deletion destroys this trial's signing/TLS identities and
database state; do it only after rollback and evidence preservation, never as a
routine restart. Keep any necessary private release evidence protected outside Git;
dispose of the workspace's temporary secrets when no longer needed. No global
Docker prune is part of cleanup.

References: [Android custom CA configuration](https://developer.android.com/privacy-and-security/security-config#ConfigCustomCa),
[Caddy local HTTPS](https://caddyserver.com/docs/automatic-https#local-https),
[Caddy Docker data directory](https://caddyserver.com/docs/running#docker-compose),
[Node extra CA trust](https://nodejs.org/api/cli.html#node_extra_ca_certsfile),
and [Android Debug Bridge](https://developer.android.com/tools/adb).

## Local validation and troubleshooting

`python3 v2/deploy/acceptance.py` builds fresh isolated state with the already-built
images, a fresh PostgreSQL, xprem and Cloud, and Caddy `tls internal`. Only ephemeral
**127.0.0.1** host ports are published. The client trusts the generated local CA using
`NODE_EXTRA_CA_CERTS`; certificate verification stays enabled. No host trust-store
change, public DNS or public ACME call occurs. It creates users through the operator
command, customer state through APIs, drives signed publication/rollback, enables all
13 Cloud tests, verifies role denial/no exposed private ports, restart persistence,
log secrecy and restores DB/state into another fresh Compose project. Containers,
networks and volumes are removed in `finally`; private evidence remains outside Git.
The payload is a transport fixture, not new source/compiler/device OTA evidence.

Run focused regressions with:

```sh
node --test --test-reporter=tap v2/deploy/config.test.mjs v2/deploy/ngrok.test.mjs v2/deploy/proxy.test.mjs v2/server/audit.test.mjs
python3 v2/deploy/acceptance.py
```

- Startup config error: verify named env/secret **file**, permissions and selected mode.
  Never paste credential values into logs/chat. Files are snapshotted at startup;
  recreate the service after atomic secret replacement.
- Not ready: verify PostgreSQL health, explicit migrations, runtime grants and image
  validator self-test. Do not switch HTTP runtime to owner credentials.
- Pending operation: inspect its Cloud operation status and the relevant binding.
  Confirm private endpoint, certificate and advertised Cloud origin. Do not expose xprem.
- TLS failure: verify DNS/firewall/hostname, CA errors and Caddy persistent volumes.
  Do not enable local HTTP or bypass certificate verification in production.
- Backup/restore failure: keep ingress closed, retain the backup, fix paths/permissions
  or disk space, retry on fresh restore volumes. A partial restore is not acceptance.
