# Native-renderer lifecycle adapter

Pinned to expo-updates 57.0.23 (MIT; see EXPO-LICENSE). Applied by patch-package
8.0.1 (MIT) at npm install. This is a Dootah-maintained source patch, **not an
upstream-supported public API**. Regenerate using an include filter limited to
`android/src/main/java/`; never include Gradle/build/.cxx outputs.

Expo's documented recovery model acknowledges rendered content:
https://docs.expo.dev/eas-update/error-recovery/
The pinned implementation exposes no native-renderer acknowledgement. The patch
adds `EnabledUpdatesController.onNativeContentRendered(UUID)` and forwards it
through StartupProcedure to the existing ErrorRecovery transition. It rejects a
stale update ID or emergency launch, and deduplicates acknowledgements. It emits
no ReactMarker, creates no React surface, and does not write the database from
Dootah. Expo's existing delegate still records success, prunes the same recovery
tasks, and removes its exception handler after the existing ten-second window.

For ABI 2, Dootah calls this only after a validated sandbox result has been drawn by Compose
and Android's frame-commit callback fires. A placeholder cannot acknowledge a remote launch. The actual embedded native screen
can acknowledge the selected embedded update after its frame commits. This spike uses frame-commit callbacks on hardware-rendered
Android 10+; older/software-rendered devices deliberately do not acknowledge and
are not covered by this experiment. It establishes first-render health, not
ongoing business-logic health or a secure JS capability sandbox.

Host verification: the patch applies cleanly to pristine expo-updates@57.0.23
npm sources; all three resulting Kotlin files match the build inputs. Device
verification belongs in docs/v2/RUNTIME_SPIKE.md. Upstream acceptance or ongoing
version-pinned maintenance remains necessary before a reusable production SDK.


Phase 6 adds `startNativeMonitoring()` without a ReactMarker listener or React host,
and `rejectNativeLaunch(UUID)` through StartupProcedure to Expo's existing DAO failed
launch counter. Selection, signature verification, asset download/cache, success
accounting and rollback remain Expo-owned. The SDK never starts ReactHost or evaluates
the downloaded launch asset as a React entry; it parses bounded Portable IR and uses
a separate AndroidX JavaScriptSandbox. The adapter itself is not the execution sandbox.

An update with a prior successful launch can remain eligible under Expo's existing
selection policy after a later rejection. Native validation still runs on every launch;
this patch does not claim permanent quarantine of a previously healthy release.
Physical Phase 6 verification is recorded in `docs/v2/SECURITY_MODEL.md`.
