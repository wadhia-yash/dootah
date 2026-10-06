# Android artifact release staging

The public SDK candidate is `0.1.0-alpha.1`. Development remains `0.7.5-local`.
Runtime ABI 2 and Logic ABI 1 are separate protocol versions. The BasicText
renderer and installed capability contracts are unchanged.

## Distribution model

The repository layout can later be uploaded to Maven Central or JFrog Artifactory;
No registry has been selected by these tools. If Central is selected, consumers put `mavenCentral()` in both
pluginManagement.repositories and dependencyResolutionManagement.repositories,
and `google()` for Android dependencies. Apply `id 'dev.dootah' version
'0.1.0-alpha.1'`; the plugin supplies the matching runtime automatically.
A Plugin Portal listing is optional and is not required for marker resolution.
No upload task or publication credentials are used by this workflow.

Public API: `dev.dootah:runtime-v2`, `dev.dootah:dootah-android-plugin` and
`dev.dootah:dev.dootah.gradle.plugin` (the marker has only a POM).
Implementation dependencies: eleven source-built Expo modules and five
npm-supplied Expo Android modules under `dev.dootah.internal`. Their original
coordinates are recorded by the staging inputs. The five upstream namespaces
are rewritten in POMs and Gradle module metadata: Dootah must not publish into
Expo's namespace. Their classes, capabilities and consumer dependency scopes
are preserved. React Native, Hermes and other public Maven dependencies remain
transitive dependencies from their existing repositories, not Dootah rehosts.
Node, Metro, Gradle/Android tooling, producer application and xprem server are
build-time or separate-server inputs, not SDK publications.

## Integration reference

Set minSdk 29 and compileSdk 36; use AGP 8.9.1 or newer and arm64-v8a. Configure
`dootah { appId = '…'; updateUrl = 'https://…/manifest'; channel = '…';
runtimeVersion = '…'; publicCertificate = file('update-certificate.pem') }`.
Use DootahApplication as the Application superclass. Ordinary ComponentActivity
and Kotlin/Compose entry functions are supported. DootahActivity is optional.
`requireHooks = true` makes zero instrumented entries fail the build. Hook reports
are at `build/outputs/dootah/<variant>/hooks.json`. Unsupported updates retain
native behavior. Only the public certificate belongs in the app.

## Local preparation

Use JDK 21, the Android SDK (including the producer's pinned NDK), Node/npm and
Python 3. Run `release/stage.sh <new-output-directory>` from any directory.
It installs the locked producer packages and applies the maintained MIT patch,
then publishes into a fresh raw repository, creates license inventories, packages
notices, rewrites rehosted coordinates, generates source-reference documentation,
checks archive contents and validates metadata before writing `repository/` plus `SHA256SUMS`. It never reads
`android-sdk/build/maven`. A previously used output path is rejected.

Sources JARs contain the actual library sources. The `javadoc` classifier contains
an HTML source reference (including source documentation comments) and this
integration guide. Kotlin/Groovy code is not passed to Java's Javadoc tool; these
nonempty documentation artifacts satisfy the Central artifact convention without
claiming to be generated API Javadoc. The marker is POM-only and needs neither.

Internal release versions include a SHA-256 digest of the final binary, source,
documentation and notice payloads plus dependency metadata, packaging implementation and configured public maintainer metadata. Changing redistributed
bytes changes the version. Public SDK versions are explicit and must be advanced
when a previously released public artifact changes. ZIP timestamps are normalized;
byte-for-byte build reproducibility is not claimed.

## License evidence

Root LICENSE covers Dootah-owned code only. Root NOTICE describes the boundary.
`inventory.json` distinguishes source-map-proven bundled JavaScript, rehosted
Android components, bundled native components and resolved Maven transitives.
Original license texts and Expo modification descriptions accompany every staged
archive under `META-INF/dootah`; AARs also carry them as Android assets so they
survive APK resource merging. Native bspatch and bzip2 notices, fbjni's Apache
license and the installed NDK's toolchain notices are included. fbjni's pinned
v0.7.0 source tree has a LICENSE and no NOTICE file. Its license is retained at
`release/licenses/fbjni-0.7.0-LICENSE`, obtained from
https://raw.githubusercontent.com/facebookincubator/fbjni/v0.7.0/LICENSE .

Native compiler dependency databases are inspected with Ninja. Pinned Folly,
Boost, fmt, glog, double-conversion and fast_float licenses accompany the SDK,
as do copyright/license preambles from the prefab headers actually compiled.
The React Native version catalog is checked against the reviewed license inventory;
an upstream version change requires a renewed license review. NDK notices are
conservative toolchain notices, not a claim that every component therein is linked.
The Javax Inject 1 POM omits its license; the inventory instead cites the Apache
license header in its Central sources JAR (`javax/inject/Inject.java`).

The inventory records transitive Maven POM licenses separately. It does not
claim that all transitive code is MIT or that a POM substitutes for a bundled
component's license text. The server/xprem is absent from Android distributions;
its community build and audit remain separate. No commercial edition source is
an input to this SDK build.

## Optional Maven Central setup (only if selected later)

1. Create a Central Portal account at https://central.sonatype.com/ .
2. Verify the `dev.dootah` namespace by proving control of `dootah.dev` using the
   Portal-provided DNS TXT record. A GitHub account alone does not establish this
   namespace. If you do not control the domain, resolve namespace ownership before
   publication; do not silently change the SDK coordinates.
3. Supply a public maintainer ID/name through DOOTAH_DEVELOPER_ID and
   DOOTAH_DEVELOPER_NAME when staging. No personal identity is inferred here.
4. Independently create/manage an OpenPGP signing key and publish its public key
   as required by Central. Keep the private key in your own GnuPG keyring or secret
   manager. Use a signing-capable subkey where appropriate.
5. Configure GPG_KEY_ID and optional GNUPGHOME locally for the signed export.
   Pinentry/your local GPG agent handles the passphrase. Never paste private keys,
   passphrases or Portal tokens into chat or commit them.
6. Manual Portal upload requires the signed repository-layout ZIP. API upload, if
   chosen later, needs a Central Portal user token username/password, stored only
   locally. This repository does not implement uploading.

Run the local checks from the repository root:

```sh
python3 -m unittest discover -s v2/android-sdk/release -p 'test_*.py'
v2/android-sdk/build-sdk.sh
v2/android-sdk/release/stage.sh /path/to/new-stage
python3 v2/android-sdk/release/consumer.py /path/to/new-stage
python3 v2/android-sdk/release/sign.py /path/to/new-stage /path/to/new-central-bundle.zip \
  --ledger /path/to/retained-coordinate-checksums.json
```

Staging is unsigned by default. The signing/export command requires GnuPG, local
signing configuration, complete developer metadata, passing content/metadata checks
and a fresh-consumer receipt bound to the exact stage checksum manifest. It verifies
each generated signature before producing the ZIP. Preserve and back up the immutable
coordinate ledger across releases; do not reset it to bypass a version conflict.
Staging paths and signed ZIP paths are never overwritten. Raw staging intermediates
are not distributions; publish only the validated, signed export. SHA-256 is the
primary manifest; Maven checksum sidecars are also emitted. The signature path uses
GnuPG's local agent/pinentry, so no password argument or committed private key exists.

Send back only namespace verification status, chosen public maintainer metadata,
and the public signing-key fingerprint after external setup. No release tag,
account, key, namespace claim or public upload is created by Phase 9B.

References: https://central.sonatype.org/publish/requirements/ ,
https://central.sonatype.org/register/namespace/ ,
https://central.sonatype.org/publish/publish-portal-upload/ ,
https://docs.gradle.org/current/userguide/preparing_to_publish.html .
