> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# Phase 2A — self-hosted server foundation

Decision: 2026-09-26. Select **xprem's MIT core**, formerly `expo-open-ota`,
from [mercuretechnologies/xprem](https://github.com/mercuretechnologies/xprem).
Pin research/initial implementation to
`b46e13569f5734a66ed903f75f51b87b78e80c1b`.
This locks the foundation, **not a deployable upstream binary**. A community-only
build is the first Phase 2B gate. No server was built or started in Phase 2A.

Phase 0 (`c70911a`) and physical-device Phase 1 (`d5fc4b8`) remain complete;
[RUNTIME_SPIKE.md](RUNTIME_SPIKE.md) contains the device evidence. No repeat was needed.
The V2 worktree was clean at entry. V1 was untouched. Its locally ignored
`DOOTAH_CURRENT.md` describes V1; the V2 phase documents govern this decision.

## Why xprem, and the licensing qualification

It already implements the closest combination of Expo delivery, publishing,
apps, channel-to-branch mapping, releases, rollback, progressive rollouts,
PostgreSQL and bucket/CDN adapters. Reusing these avoids writing a protocol,
signer or release engine. Native Compose and the Phase 1 patched expo-updates
client remain unchanged architectural responsibilities.

The [pinned license](https://github.com/mercuretechnologies/xprem/blob/b46e13569f5734a66ed903f75f51b87b78e80c1b/LICENSE.md)
is MIT **except every directory named `ee/`**, which is commercial. MIT permits
commercial use and modification with notices preserved; it contains no
restriction on operating Dootah Cloud. This does not license enterprise code
or establish licenses for all transitive dependencies.

**New finding:** the default distribution is mixed-license. The
[Dockerfile](https://github.com/mercuretechnologies/xprem/blob/b46e13569f5734a66ed903f75f51b87b78e80c1b/Dockerfile)
copies `ee`; the MIT
[router wiring](https://github.com/mercuretechnologies/xprem/blob/b46e13569f5734a66ed903f75f51b87b78e80c1b/internal/router/wire.go)
and access/routes files import enterprise packages without build constraints.
Disabling enterprise features does not exclude their source from the binary.
Do not use the published image or assume `go build ./cmd/api` is MIT-only.

Phase 2B may adapt MIT composition/wiring to omit enterprise modules and expose
existing MIT services. Do not copy enterprise implementations, replace license
checks to unlock them, or build a parallel protocol engine. A targeted source
check of 63 production Go files in `internal/services`, `internal/handlers`,
`internal/repository` and `config` found no direct `xprem/ee/` imports. This supports
feasibility but is **not** a complete transitive import audit or successful build.
If separation requires substantial subsystem replacement, stop and revisit the
maintained MIT alternative below rather than inventing a server.

## Candidate comparison

Primary evidence: repository README/source/license and GitHub API default-branch
commit metadata, checked on the decision date. All six repositories below were
unarchived. Dates measure observed activity, not a maintenance SLA. Compatibility
is documented/source-supported here; only Phase 2B can prove our SDK 57 device path.
“Not verified” does not assert that a feature is impossible.

| Candidate / observed latest default-branch commit | License and protocol | Product model, persistence and storage | Rollout / SaaS fit / decision |
| --- | --- | --- | --- |
| **xprem**, `b46e135`, 2026-09-25 | MIT core; commercial `ee/`. Official Expo protocol implementation with manifest/signing and rollback tests. | Multiple apps; branches and channels; publish/republish/releases/manual rollback. PostgreSQL control plane; local files, S3-compatible, R2, GCS, Azure and CDN adapters. | Percentage rollout code is outside `ee/` and requires database mode. App separation is useful but not proven tenant isolation. **Selected MIT core only**, with composition gate above. |
| **[umbertoghio/self-hosted-expo-updates-server](https://github.com/umbertoghio/self-hosted-expo-updates-server/tree/73256658ef4fdd1e1cb97bdc496e694139e6ee8d)**, `7325665`, 2026-09-09 | [MIT](https://github.com/umbertoghio/self-hosted-expo-updates-server/blob/73256658ef4fdd1e1cb97bdc496e694139e6ee8d/LICENSE); Expo manifests/signing; SDK 55+ patch support documented. | Multiple apps, versions/release channels, upload then release, previous-update rollback. Bun/Feathers v5, MongoDB, filesystem upload/update volumes. Independent branch mapping and S3 adapter not verified. | Percentage rollout and tenant isolation not verified. Strongest maintained fallback without an enterprise source split; more storage/control-plane adaptation. Shipped production compose still uses MongoDB 4.2.2 and amd64 images: not a deployment template to adopt unchanged. MongoDB server licensing is separate from this MIT application. |
| **[zhxycn/expo-updates-server](https://github.com/zhxycn/expo-updates-server/tree/b4bf155b38b4a329144d6aff1fa297c22a591441)**, `b4bf155`, 2026-05-22 | [MIT](https://github.com/zhxycn/expo-updates-server/blob/b4bf155b38b4a329144d6aff1fa297c22a591441/LICENSE); documented Expo v0/v1, signing, no-update and embedded rollback directives. | Projects/members/project keys; project serves as channel, runtime-scoped timestamp updates; SQLite/Bun Go ORM, local/S3 storage. Separate branch mapping not documented. | Multi-tenant model exists; percentage rollout not verified. Smaller delivery foundation with more release-model work; less recent activity. |
| **[dnlsilva/open-ota](https://github.com/dnlsilva/open-ota/tree/2d1046d58ccea45187834eb8faffd6cd75f392da)**, `2d1046d`, 2026-09-02 (repository push 09-11) | [MIT](https://github.com/dnlsilva/open-ota/blob/2d1046d58ccea45187834eb8faffd6cd75f392da/LICENSE). README integration uses its own `@open-ota/react-native` client; stock expo-updates wire compatibility not established. | Projects, channels, signed releases, rollback; Postgres, S3/R2/Supabase/local adapters. | Percentage rollouts and hosted organizations/billing advertised. Reject for this pivot: would replace the proven client/update lifecycle without evidence of protocol compatibility. |

Also screened [glncy/expo-up](https://github.com/glncy/expo-up)
(`094748d`, 2026-03-26): claims Expo delivery/channels/rollback, but GitHub's license
endpoint returned no license; reuse rights were not verified. Not shortlisted.
[xavia-io/xavia-ota](https://github.com/xavia-io/xavia-ota) has MIT metadata but its
latest default-branch commit was `4558c9c`, 2025-12-12; not preferred over recently
maintained candidates. Expo's official custom server remains a demonstration
fixture, not the production foundation (see [DEPENDENCIES.md](DEPENDENCIES.md)).

## Architecture and ownership

```text
Dootah-owned publish adapter -> xprem MIT release/storage services
                                      | PostgreSQL + local storage initially
                                      | existing S3/R2/CDN adapters later
Native APK / expo-updates -> Dootah-owned URL -> xprem MIT manifest/signing services
                         -> downloaded Hermes JS -> Dootah bridge -> native Compose
```

No EAS account, EAS API or hosted Expo service is needed in this delivery path.
Expo's [protocol](https://docs.expo.dev/technical-specs/expo-updates-1/) is the wire
contract; internal export tooling is not an EAS dependency. Production routing
will use `updates.dootah.dev`; that domain is not deployed by this decision.

Reuse MIT publish/release/rollback, runtime/platform selection, channel/branch
mapping, rollout bucketing, signing, migrations and storage adapters. Retain
expo-updates client verification/cache/recovery and the native health adapter.

Dootah builds only the MIT-only composition adaptation, a narrow publish adapter
and compatibility/release metadata mapping now. Later it owns customer accounts,
organization authorization, installation/adoption telemetry, billing, dashboard
and product-level pause/kill semantics, using established infrastructure. Keep
backend administration private behind Dootah authorization; an app ID is not a
tenant security boundary. Dootah release versions map to upstream update IDs;
never equate an arbitrary integer with Expo's timestamp-based selection rules.

The [upstream community policy](https://github.com/mercuretechnologies/xprem/blob/b46e13569f5734a66ed903f75f51b87b78e80c1b/CONTRIBUTING.md)
reserves advanced analytics, Observe, automatic rollback, SSO, audit and other
organization features for commercial code. Dootah must implement its product
behavior independently using MIT services/ordinary infrastructure, not copy `ee/`.

## Risks and exact Phase 2B sequence

1. **First action:** obtain an isolated source checkout at the pinned SHA outside
   the V1 worktree; inventory all direct/transitive `ee/` imports reachable from
   the API entry point and retained packages. Record an MIT-only composition plan
   and notices. Do this before Docker or backend startup. No presumed community
   build flag was found; prove separation rather than assuming one exists.
2. Make only the necessary MIT wiring changes. Exclude every `ee/` directory from
   build inputs, omit the dashboard initially, and inspect `go list -deps` plus
   the resulting artifact inputs. Review dependency licenses and run focused
   upstream manifest/signing/rollback/rollout tests. If this cannot pass without
   substantial replacement, stop and reassess the fallback.
3. Run the resulting server with PostgreSQL and persistent local asset storage,
   using its existing adapters/migrations. Record tool versions, migration state,
   storage paths and start/stop commands. Add S3-compatible storage only through
   the existing adapter; no new storage/protocol/signing implementation.
4. Create one app, branch/channel and runtime matching the Phase 1 APK. Use a
   minimal adapter to upload the existing exported artifact through upstream
   publish APIs. Verify app/runtime/platform/channel isolation, asset hashes,
   signing failure behavior and metadata survival across server restart.
5. Route the APK's existing fixture address to the new backend where feasible;
   if native URL/header/signing configuration requires a new baseline, record a
   fresh install-once proof. Never reinstall during its OTA/restart/rollback run.
   Repeat baseline -> remote Compose title -> online restart -> offline cache ->
   rollback on the physical device with update IDs, health logs and screenshots.
6. Test rollback via upstream republish/new timestamp or supported embedded
   directive; channel remapping alone must not be assumed to displace newer cached
   updates. Verify no EAS traffic. Record release/version mapping and exact receipts
   before considering the Phase 2 commit. No SaaS dashboard yet.

Remaining risks: MIT-only composition is unbuilt; SDK 57 conformance and signing
remain untested against xprem; upstream API/schema churn needs pinned upgrades;
percentage bucketing needs a stable installation identifier; pause is not removal
of already cached updates; kill cannot reach offline devices. App isolation,
key separation and telemetry are not proven by multi-app support.

**Phase 2A complete.** Subsequent Phase 2B host implementation and acceptance are
recorded in [SELF_HOSTED_SERVER.md](SELF_HOSTED_SERVER.md). The default upstream
image remains mixed-license; the proved composition excludes the upstream router
and all `ee/` source. Phase 2C device acceptance has not begun.
