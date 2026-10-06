> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). This records a bounded
> deployment permission fix and smoke test, not completed Phase 9G acceptance.

# Phase 9G — 9G-BUG-003 permission fix and preflight

2026-10-04 (Asia/Kolkata). Starting HEAD:
`1237187ba7542686abf1d6bcf006e3dab837ee5c`.

**9G-BUG-003 fixed and validated under umask 077.** The separate ngrok deployment
smoke is **not green**: it exposed **DOCUMENTATION_BUG / 9G-DOC-004**. Full Phase 9G
was not restarted. Phase 9H was not started. The three earlier failed attempts
remain historical evidence and were not rewritten as passes.

## Root cause and bounded fix

The deployment builder copied private host files into its temporary context.
Docker preserved root-owned 0600 source files and restrictive directory modes.
Cloud's intentional privilege drop to UID/GID 10001 then made `admin.mjs` unreadable.
The images had not established their own immutable-payload permissions.

The image definitions now establish root ownership, 0755 directories, readable
immutable files and 0755 executable entrypoints. Cloud normalizes only `/app` and
`/licenses`, preserving executable dependency files; its ordinary source/assets,
validator JAR and class files are 0644. xprem's binary and entrypoint are explicitly
0755, its notices are 0644, and `/state` is 0700 owned by UID/GID 10001.

The entrypoint keeps a private umask and a 0700 storage root. It still snapshots
0400/0600 secret inputs into a UID/GID-10001-owned 0700 directory with 0600 files,
then drops privileges before running the service. No secret input was normalized
with the immutable image payload. No root HTTP service, chmod 777, ABI, capability,
grammar, update-signing, JavaScriptSandbox, renderer or OTA semantic change was made.

The bounded audit also found that Docker Desktop's host-file sharing allowed UID
10002 to open a bind-mounted 0600 probe despite its reported root ownership. Both
images now create `/run/input` as root-owned 0700. Root startup can read the source
mounts, but non-root processes cannot traverse that directory. The audit verifies
actual read denial, not mode bits alone. This hardens the same file-permission
boundary without changing host secret modes or removing the privilege drop.

## Audited classes

| Image / class | Result |
| --- | --- |
| Cloud admin/server, adapter/config/core/worker modules | Root-owned 0644; readable and not writable by UID 10001 |
| Cloud SQL migrations, API test modules, package files/dependencies | Immutable payload audit passed; migrations and admin commands exercised |
| Cloud HTML/CSS/JS dashboard assets | Root-owned 0644; runtime-readable |
| Cloud packaged validator classes/JAR and Java | Readable/traversable; validator self-test passed as UID 10001 |
| Cloud shared publisher module | Root-owned 0644; signed operations exercised |
| Both entrypoints | Root-owned 0755; actual privilege-drop path exercised |
| xprem executable, provisioning and migration tooling | Root-owned 0755 binary; migration/provisioning executed in integration |
| xprem persistent storage | UID/GID 10001, 0700 root; write, backup and restore exercised |
| Immutable license/notice directories | Root-owned 0755 directories and readable files |
| Source secret mount directories | Root-owned 0700; unrelated UID cannot traverse/open inputs |
| Runtime secret snapshots | UID/GID 10001, 0700 directory / 0600 files; unrelated UID denied |
| Caddy configuration | Read-only host mount, loaded by upstream root process; 077-created copy validated successfully |
| Operator tooling | `ops.py` runs on the host; container admin/migration commands use the audited JS/binary and entrypoint |

No separate mutable configuration-template tree was found in either production
image. Runtime configuration arrives through environment and protected secret mounts.

## Regression and local validation

`v2/deploy/build.sh` now always creates its build context under `umask 077`, regardless
of the caller's reasonable umask, and finishes by running
`v2/deploy/image_permissions.py`. The audit checks both built images offline before
deployment, runs the validator as the actual runtime UID, verifies privilege drop,
checks source/snapshot secrecy and rejects an insecure 0644 input.

The audit rejected the original broken image before the fix. The final supported
build completed successfully under 077, including its mandatory image audit. The
fresh upstream checkout and Go cache volumes were newly created for this work;
Docker build layers were reused where inputs matched. No bit-for-bit image-build
reproducibility claim is made.

The existing deployment integration test now additionally checks live snapshot
permissions and denial to another UID. It ran under 077 with fresh state and passed:

- bootstrap, Cloud and xprem migrations, initial users and non-root startup;
- trusted local HTTPS health and actual UID/GID 10001 for both HTTP processes;
- organization/application/environment registration, provisioning, signed publish
  and rollback, key rotation and refusal paths;
- all 13 Cloud tests, with zero failures or skips;
- runtime DB privilege denial, restart persistence and credential-log checks;
- fresh-volume backup/restore with identical storage hashes and verified signatures;
- teardown of both integration Compose projects.

Additional checks passed: 345 xprem tests with the four documented Azure exclusions;
12 deployment/server-audit tests; 12 CI regressions; 13 packaging regressions;
9 CLI/portable tests; 4 documentation regression tests; 24-public-guide validation;
shell/Python syntax and whitespace checks. The publisher distribution built
successfully; its unchanged Gradle tests were up to date. Android/Compose gates and
the full SDK candidate suite were not rerun for this deployment-only change.
Reviewed Dockerfile hashes in `v2/ci/pins.json` were updated; base-image/dependency
versions remain unchanged. Hosted V2 CI remains **UNVERIFIED**.

## Separate fresh ngrok smoke: 9G-DOC-004

Classification: **DOCUMENTATION_BUG** in the ngrok guide's effective local routing
on the tested Docker Desktop setup. This is distinct from the fixed permission bug.

New operator secrets, PostgreSQL state, application service containers and xprem
storage were created. Bootstrap, both migrations, grants, initial Cloud user and
Cloud/xprem startup all succeeded under 077. A single authenticated ngrok HTTPS
tunnel targeted only `http://127.0.0.1:18080`; no token was read/printed, and neither
xprem nor PostgreSQL was tunneled or host-published.

However, the documented override leaves Cloud attached only to the base Compose
`internal: true` ingress/backend networks. Inspection showed:

```text
Effective Compose request: 127.0.0.1:18080 -> Cloud :3100
HostConfig.PortBindings: {"3100/tcp":[{"HostIp":"127.0.0.1","HostPort":"18080"}]}
NetworkSettings.Ports:   {"3100/tcp":[]}
Host curl 127.0.0.1:18080/live: connection refused
Inside Cloud: /live 200 live; /ready 200 ready
Cloud and xprem PID 1: UID/GID 10001; umask 0077
```

Initial public ngrok curl and Node probes returned 502; TLS verification was not
bypassed. The failure persisted as a missing host listener after the services were
healthy. On continuation the temporary agent was no longer running and the old
public URL returned 404; that later 404 is not used as evidence of Cloud routing.
The loopback inspection independently establishes that the documented upstream
was unavailable. No universal claim about every Docker platform is made.

No routing override, extra Docker network, direct backend tunnel or product
workaround was attempted. Customer CLI access through ngrok was not reached, and
public `/live` and `/ready` are **FAIL**, not 200. The public guide has not been
silently repaired. Per the user's smoke-test stop rule, further topology correction
requires authorization before a full clean-room run can start.

| Requested outcome | Result |
| --- | --- |
| Generic container permission fix | PASS |
| Production image build context under umask 077 | PASS |
| Cloud runtime UID / xprem runtime UID | 10001 / 10001 |
| Protected secret input/snapshot permissions | PASS; no weakening |
| Broader private production-stack integration and restore | PASS |
| Fresh ngrok smoke bootstrap/migrations/user/startup | PASS |
| ngrok HTTPS `/live` | FAIL — initial 502, upstream listener unavailable |
| ngrok HTTPS `/ready` | FAIL — initial 502, upstream listener unavailable |
| Customer CLI through ngrok | NOT RUN — blocked by preflight |
| Deployment smoke overall | FAIL — DOCUMENTATION_BUG / 9G-DOC-004 |
| Full Phase 9G restarted | NO |
| Phase 9H started | NO |

## Cleanup and evidence

Both automated integration projects were cleaned by the acceptance runner. The
separate smoke stack's containers, networks and volumes and the fresh Go cache
volumes were removed. The ngrok agent is stopped. Temporary smoke operator secrets
were removed. No APK/device changes or adb reverse rules occurred. Unrelated
Docker resources and the user's ngrok configuration were not touched.

Sanitized [result](evidence/phase9g-permissions-20261004/result.json),
[image audit](evidence/phase9g-permissions-20261004/image-permissions.json) and
[integration receipt](evidence/phase9g-permissions-20261004/acceptance.json) are
retained. Raw logs, databases, tokens and private keys are excluded from Git.
No public artifact or production external deployment is claimed.
