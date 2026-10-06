# Dootah alpha security model

This is the current public model for Runtime ABI **2**, Logic ABI **1** and SDK
**0.1.0-alpha.1** (development **0.7.5-local**). It describes layered controls and
remaining assumptions, not a security certification or an absolute guarantee.

## Delivery and installed trust

The APK embeds the application's public update certificate and compatibility
configuration. Native Expo Updates verifies signed manifests/directives and the
hashes of downloaded assets before selecting an update. Hashes bind artifact bytes
to the signed manifest; signatures authenticate the installed signing identity.
They do not prove freshness on their own, correct business rules or a safe server.

Publishers send Portable IR as data. Cloud validates it against the registered
immutable contract; the device independently validates its schema, Runtime ABI,
Logic ABI, runtime version, required capabilities, types and budgets. Dispatch binds
inputs to an installed function identity and portable signature. Unknown fields,
operations and incompatible contracts are rejected. Retained APK/source/class hashes
also protect the publishing comparison from an accidentally changed baseline.

## Isolated execution and typed native capabilities

The selected launch file is bounded IR JSON. The installed shared
[PortableProgram](../../v2/portable/src/main/java/dev/dootah/portable/PortableProgram.java)
validator generates executable JavaScript; it never evaluates publisher-supplied JS.
The current Application creates no ReactHost and exposes no RN/Expo module registry.
Packaged RN/Hermes libraries are not a permission grant to remote logic.

[RestrictedSandbox](../../v2/android-sdk/runtime/src/main/java/dev/dootah/runtime/sandbox/RestrictedSandbox.java)
uses AndroidX JavaScriptSandbox's separate isolated process and a fresh isolate per
invocation. No Context, JVM object, host callback, file descriptor, module resolver or
network capability is passed in. Scalar input strings and validated result strings
cross the boundary. The closed source/IR grammar excludes imports, dynamic code,
time/random and async; the sandbox is a separate containment layer, not a claim
that a generic JavaScript engine has no such language features.

Only the installed `logic.pure.v1` and `compose.basicText.v1` capabilities are exposed.
Native code owns rendering. A new capability requires an installed native implementation
and compatible contract, normally delivered in a new APK.

| Limit | Enforcement |
| --- | --- |
| Artifact / JSON nesting | 65,536 UTF-8 bytes / 64 levels, checked before recursive parsing |
| Program shape | 32 entries, 9 scalar inputs per entry |
| Work / expression depth | 512 expanded IR nodes per entry / 24 levels, checked natively; all branches count |
| Text | 256 UTF-16 units, valid surrogate pairs, nonblank render result |
| Sandbox source / result | 64 KiB / 16 KiB |
| Isolate heap | 8 MiB requested from the provider; not a cap on total process memory |
| Evaluation | 1-second future deadline, then isolate closure/termination; not a real-time deadline for all app work |
| Dispatch cache / pending work | 128 distinct invocations per process / at most 32 pending |

The provider must support isolate termination and heap limits. Creation has a
10-second connection wait on worker code. Missing features, malformed payloads,
timeouts, execution failure and invalid results retain native behavior. Closing an
isolate and OS scheduling are not a universal availability guarantee.

## Fallback, recovery and rollback

The original Compose body remains installed. It runs until a valid remote result is
available and whenever dispatch cannot accept one. Successful download or calculation
alone does not acknowledge rendered health. Native frame acknowledgement participates
in the updater's recovery mechanism.

Operators can request signed rollback to the embedded/native baseline. Pause only
stops new offers; it does not revoke cached execution. Offline rollback/kill is not
instantaneous. Recovery cannot undo already completed business decisions. See
[delivery and rollback](../concepts/delivery.md).

## Cloud and private backend

Cloud uses expiring sessions and scoped tokens, organization membership/roles and
app/environment authorization. Publication and release controls require their
respective scopes; optimistic versions prevent stale control writes, and explicit
idempotency keys handle ambiguous publish/rollback retries. Registration checks
immutable installed contracts. Customer access is through authenticated APIs.

Native enrollment exchanges a ten-minute one-use ticket for a 30-day installation
credential in app-private storage. A legitimate authenticated customer backend must
issue tickets and handle renewal. Publisher/operator secrets never belong in the APK.
Telemetry is best effort; a healthy render is not a crash-free metric.

The production Compose shape exposes only Caddy's HTTPS route to Cloud. xprem and
PostgreSQL have no public host ports. xprem's server-side upload credential, signing
keys and master key remain operator-controlled. Runtime database roles cannot run
migrations; migrations are explicit operator actions. See
[self-hosting](../self-hosting/README.md) for backups and private networking.

## Trust assumptions and limits

Trust the installed APK/native code, OS, WebView/sandbox provider, build dependencies,
operator host/Docker administrators and installed public trust configuration. Rooted
or compromised devices and native engine/OS vulnerabilities are outside the guaranteed
boundary. A compromised signer can authorize malicious business logic within the
accepted grammar; isolation cannot decide whether a discount or label is correct.

Keep signing identities, xprem master key, database state and backups under controlled
custody. Losing them can prevent decrypting signing material or issuing updates and
rollback accepted by existing APKs. Changing the trust anchor is not solved by editing
an OTA program. Restore needs matching secrets and storage, not only a database dump.

Local negative tests and one physical Android 13 device support the documented model.
There has been no claim of independent security audit, broad device qualification,
standardized SBOM, comprehensive vulnerability scan or guaranteed protection against
all denial-of-service attacks. Historical investigation of the superseded shared host
is preserved in [the security record](../v2/SECURITY_MODEL.md); its failures are not the
current design. Report vulnerabilities privately as described in
[SECURITY.md](../../SECURITY.md). Contributor expectations are in [CONTRIBUTING](../../CONTRIBUTING.md).
