> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). This is a small ingress
> preflight, not completed Phase 9G acceptance.

# Phase 9G — 9G-DOC-004 Caddy ingress correction

2026-10-04. Starting HEAD: `99ec6e61e607bff2e75535873d5b1060db3bcbf2`.

**Small preflight: PASS.** The original failed 9G-DOC-004 evidence in
[the permission-fix report](PHASE9G_PERMISSION_FIX.md) remains unchanged.

The supported alpha overlay replaces Caddy's production host bindings with only
`127.0.0.1:18080:80`. Its dedicated HTTP configuration proxies to private
`cloud:3100`. Cloud retains its internal ingress/backend networks; xprem and
PostgreSQL retain their internal backend network. None of those three services
publishes a host port. The production Compose topology and Caddy configuration
are unchanged. No runtime, ABI, capability, OTA signing or secret-permission change
was made.

A new workspace and operator secrets, PostgreSQL volume, xprem storage and Cloud
state were created under umask 077. This bounded preflight reused the audited
production images from the preceding permission fix and the built customer CLI;
it is not a fresh SDK/artifact or device acceptance claim.

Bootstrap, both migrations, grants and initial user creation succeeded. PostgreSQL,
xprem and Cloud became healthy. Host curl through loopback Caddy returned 200 with
the expected plain `live` and `ready` bodies. The existing authenticated host ngrok
installation then created exactly one HTTPS tunnel to that Caddy listener. The
ngrok token was neither read nor printed; request inspection was disabled.

After replacing the preflight-only bootstrap origin with the final ngrok HTTPS
origin, Cloud and xprem were recreated. Inspection confirmed matching advertised
public origins and the unchanged private `http://xprem:3100` origin. Both public
health probes returned 200 with normal curl and Node certificate verification.
The supported customer CLI logged in using a password file and successfully listed
the new user's empty organization collection.

Live Docker inspection confirmed Cloud/xprem/PostgreSQL host bindings were empty,
and Caddy alone bound `127.0.0.1:18080`. HTTPS transport trust supplied by ngrok is
separate from Dootah's per-app OTA update-signing certificate; this small preflight
did not provision an app, build/install an APK or claim device OTA acceptance.

Two new automated regressions inspect effective Compose configuration with hostile
base port defaults and the Caddy-adapted routing configuration. They require only
loopback proxy publishing, private service networks, a read-only Caddy config and
the sole upstream `cloud:3100`. They are included in the required deployment suite.
Validation passed: 14 deployment/server tests, 12 CI regressions, 4 documentation
regressions and validation of all 24 public guides. No full SDK suite was run for
this configuration correction. GitHub-hosted V2 CI remains **UNVERIFIED**.

The temporary tunnel, containers, networks, volumes, operator secrets and customer
session were removed. Unrelated resources were untouched. No device changes or
adb rules were made. The [sanitized receipt](evidence/phase9g-caddy-preflight-20261004/result.json)
preserves image IDs, health results and bindings without credentials or raw logs.

This permits a new clean-room Phase 9G attempt. It does not establish Maven/JFrog
availability, production self-host deployment, custom-domain TLS or Internet-scale
operation. Phase 9H was not started.
