> Engineering history / dated acceptance record. For current setup and support,
> start at the [public documentation index](../README.md). Earlier statements below
> may describe superseded versions or intermediate failures; retain them as evidence.

# V2 dependency decisions

Researched 2026-09-26 using upstream docs, source, npm metadata and GitHub APIs.
This is a direct-component decision record, not a completed transitive license audit.
No third-party source has been vendored by Phase 0.

## Runtime selection

The npm `latest` checks returned Expo **57.0.25**, expo-updates **57.0.23**,
expo-brownfield **57.0.23**, and React Native **0.87.1**. Use SDK 57's compatible
RN **0.86.3** and React **19.2.3**, not independent latest versions. The
[SDK 57 matrix](https://github.com/expo/expo/blob/sdk-57/packages/expo/bundledNativeModules.json)
is the compatibility source. Expo main already targets an SDK 58/RN prerelease family;
do not accidentally build against main. Lock exact resolved dependencies in the spike.

| Dependency | Purpose | License / evidence | Use / modifications | Maintenance status | Major risk |
| --- | --- | --- | --- | --- | --- |
| expo-updates 57.0.23 | Download, verification, persistence, selection, launch/recovery | MIT; [Expo license](https://github.com/expo/expo/blob/sdk-57/LICENSE), npm metadata | Link pinned upstream with a three-file MIT native-render acknowledgement patch; see spike patches/README.md | Active Expo SDK 57 release | No upstream public native-render health API; Dootah must maintain or upstream this adapter. |
| Expo Updates protocol v1 | Wire contract | [Official specification](https://docs.expo.dev/technical-specs/expo-updates-1/); implementation licensing follows the implementing project | Use specification; no copied protocol implementation | Current published protocol | Protocol conformance alone does not supply tenant isolation or kill semantics. |
| expo 57.0.25 and expo-modules-core SDK 57 | Host factory, module registration and lifecycle | MIT; Expo license above | Link only required modules | Active SDK 57 | Autolinking/build tooling and native API churn; default capability exposure. |
| expo-brownfield 57.0.23 | Android artifact packaging and JS/native messaging | MIT; Expo license and npm metadata | Use upstream tooling/templates; Dootah wrapper as needed | Active official package; recent isolated-integration documentation | AAR dependency/assets publication and headless initialization need validation. |
| react-native 0.86.3 | Hermes host, bridge and runtime lifecycle | MIT; [license](https://github.com/facebook/react-native/blob/v0.86.3/LICENSE), npm metadata | Link; no RN screen or consumer RN sources | Active upstream; SDK-compatible release, latest RN is 0.87.1 | APK/memory overhead and native dependency collisions. |
| Hermes bundled with RN 0.86.3 | Execute exported JS/bytecode | MIT; [license](https://github.com/facebook/hermes/blob/main/LICENSE) | Link RN-matched artifact; no standalone engine fork | Maintained alongside RN | Bytecode/native ABI mismatch; do not independently upgrade. |
| React 19.2.3 | Internal RN dependency | MIT; [license](https://github.com/facebook/react/blob/v19.2.3/LICENSE), npm metadata | Link internally; no React UI | Active upstream | Dependency overhead despite no React surface. |
| Expo CLI / Metro matching SDK 57 | Internal JS export/bundling | MIT; [Expo license](https://github.com/expo/expo/blob/sdk-57/LICENSE), [Metro license](https://github.com/facebook/metro/blob/main/LICENSE) | Build tooling within Dootah; no consumer maintenance | Active upstream | Hidden build pipeline must ship reproducibly; no EAS publish/build command. |
| AndroidX Compose | Actual native UI | Apache-2.0; [AndroidX license](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) | Link normal Android dependencies | Active AndroidX | Host Compose binary compatibility; measure with one pinned sample toolchain. |

Phase 1 adds `patch-package` **8.0.1** (MIT, David Sheldrick; license shipped in
npm package) as internal build tooling. It applies the pinned Expo source patch;
no patch-package modifications or vendoring. It is an established tool; the main
risk is patch drift on dependency upgrades. Installation fails if patch application
fails. Expo's MIT notice accompanies the patch. No commercial source is used.

## Server selection and rejected alternatives

| Component | Purpose | License / evidence | Use / modifications | Maintenance status | Major risk |
| --- | --- | --- | --- | --- | --- |
| expo/custom-expo-updates-server at `feb29fc4ac1f5eb011bdfe95391190a91da4bb31` | Phase 1 local protocol fixture | MIT; [license](https://github.com/expo/custom-expo-updates-server/blob/feb29fc4ac1f5eb011bdfe95391190a91da4bb31/LICENSE) | Run existing server separately; configure paths/URL, preserve notices | Official demonstration; [upstream explicitly disclaims production completeness](https://github.com/expo/custom-expo-updates-server) | Not the Phase 2 production foundation. |
| xprem v3.2.4 (2026-09-23); researched main `b46e13569f5734a66ed903f75f51b87b78e80c1b` | Selected Phase 2 protocol/storage/release foundation | MIT outside all `ee/` directories; [exact license](https://github.com/mercuretechnologies/xprem/blob/b46e13569f5734a66ed903f75f51b87b78e80c1b/LICENSE.md) | Build/run community core; smallest external Dootah adapter; no commercial-source copying | Not archived; API reports push 2026-09-25; formerly expo-open-ota | Open-core boundary; verify community build excludes enterprise source before reuse. |
| umbertoghio/self-hosted-expo-updates-server | Alternative investigated | MIT; [repository/license](https://github.com/umbertoghio/self-hosted-expo-updates-server) | Not selected or vendored | README documents current SDK 55+ patch support and Bun/Feathers v5 | MongoDB-based stack differs from preferred Postgres foundation; no independent conformance proof. |
| PostgreSQL | Phase 2 durable metadata; use selected core's adapter | PostgreSQL License; [license](https://www.postgresql.org/about/licence/) | Use established server/driver; exact version deferred until Phase 2 | Maintained upstream | Tenant boundaries and migrations still require Dootah validation. |
| Local filesystem, later S3/R2 through xprem storage adapter | Assets | Adapter follows xprem community license; service terms separate | Reuse existing adapter; no new object-store client | Existing upstream storage support | Asset URL reachability, immutable objects and CDN caching. |

xprem's [community policy](https://github.com/mercuretechnologies/xprem/blob/b46e13569f5734a66ed903f75f51b87b78e80c1b/CONTRIBUTING.md)
places advanced analytics, Expo Observe support, automatic rollback, SSO/SAML, audit
logs and other features in the commercial edition. Do not copy them or assume its
published container excludes them. Validate the MIT build path first. Dootah's later
product telemetry/control layer must be independently implemented using established
OSS infrastructure and community APIs, with no enterprise source reuse.

No EAS service, EAS account, Expo-hosted release endpoint or proprietary xprem feature
is selected. No Redis, MinIO, authentication framework or analytics store is currently
adopted: choose only when the relevant phase requires it and check its exact license.
Do not infer that an entire transitive graph is MIT from its top-level package.

## Reproducibility before shipping

Use npm lock integrity and immutable server revisions; inspect actual package LICENSE
and notice files, Gradle dependency graph, AAR native libraries and any generated
artifact dependencies. Record the exact Hermes artifact selected by RN. Produce
third-party notices before distribution. Recheck license boundaries on upgrades.
Phase 0 selected reusable components; it did not validate release binaries or security.

Phase 2A source review: the default xprem API wiring imports `ee/` packages and
the Dockerfile copies them. No MIT-only binary is approved yet. See
[SERVER_FOUNDATION.md](SERVER_FOUNDATION.md) for the pinned comparison, required
composition adaptation and exact Phase 2B gates.

Phase 2B: [SELF_HOSTED_SERVER.md](SELF_HOSTED_SERVER.md) records the now-proved
composition and [dependency license inventory](evidence/phase2b-20260926/licenses.csv).
All commercial directories and the upstream router are absent from retained build
inputs. Third-party dependencies include Apache/BSD/MIT/MIT-0 and MPL-2.0 (River);
"MIT-only xprem" does not mean all dependencies are MIT. No binary is distributed
by this commit. The default upstream image is still not selected.
