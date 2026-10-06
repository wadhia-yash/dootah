# Self-host Dootah Cloud

The alpha deployment is an operator-managed, single-host Docker Compose stack:

```text
Internet -> HTTPS / Caddy -> Cloud -> private xprem / PostgreSQL
```

Only Caddy publishes TCP 80/443. Cloud is the public application route behind it;
xprem and PostgreSQL have no host port mappings. Caddy cannot join the backend
network. The deployment does not provide a managed Dootah service or public xprem
administration endpoint.

Follow the canonical [deployment and operations guide](../../v2/deploy/DEPLOYMENT.md)
for exact commands. It covers prerequisites, domain/TLS configuration, secret files,
pinned image builds, database bootstrap, explicit migrations, initial user creation,
per-app xprem bindings/public certificate delivery, health checks, rotation,
backup/restore and upgrades. Keep that guide as the operational source of truth.

For **ALPHA / CLEAN-ROOM VALIDATION USING NGROK**, follow the
[ngrok validation guide](ngrok.md). It tunnels only Caddy's loopback HTTP listener,
keeps Cloud/xprem/PostgreSQL private, and uses normal client HTTPS trust. This is a
validation alternative, not the production deployment architecture above.
The earlier [private-CA walkthrough](../../v2/deploy/DEPLOYMENT.md#persistent-private-https-for-a-physical-android-device)
has an unresolved TLS preflight failure and is not the current acceptance route.

Use Ubuntu 24.04 with Docker/Compose on ARM64 or AMD64. Plan for 2–4 vCPU, 8 GiB RAM
and 60 GiB SSD for a small controlled alpha; these are estimates, not load-tested
capacity promises. The local qualification used Docker Desktop ARM64 and emulated
AMD64; no external deployment or native Ubuntu host has been qualified here.

Operators provision Cloud users and app delivery bindings. Customers create orgs/apps
and register installed contracts through the [CLI](../getting-started/quickstart.md).
There is no zero-configuration production enrollment: the customer still needs an
authenticated backend to mint short-lived tickets for its native integration.

HTTP startup does not migrate. Apply migrations explicitly with the owner credentials;
run services with restricted runtime roles. Preserve persistent volumes and maintain
encrypted off-host backups of databases, assets and matching secrets. Rehearse restore.
Loss of xprem signing/master-key material can make existing keys unusable and prevent
future updates/rollback for APKs trusting that identity.

This alpha has no HA, clustering, PITR, automated retention, managed alerts or
zero-downtime upgrades. The [security model](../security/README.md) describes trusted
operators and network boundaries. These pages are instructions, not evidence that
infrastructure has been provisioned.
