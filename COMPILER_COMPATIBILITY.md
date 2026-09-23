# Compiler compatibility

Dootah's Gradle plugin selects the compiler backend. Applications never select
an adapter and must not change Kotlin merely to enable Dootah.

Kotlin compiler internals have no stable binary compatibility contract. Dootah
therefore accepts only compiler/family pairings that passed its compatibility
suite. Closeness of version numbers is not evidence. An unverified patch stays
native during an ordinary Android build; an explicit OTA operation refuses it
with the detected compiler and verified families. This does not claim that
arbitrary future compilers are compatible.

## Implementation and verification are separate

`gradle/compiler-adapters.properties` defines ABI implementations: the compiler
used to build each binary, the small source bridges it uses, and its Compose
ordering mechanism. A family name identifies an implementation, not a semver
range. Different compiler generations can share an implementation when tests
prove it works.

`gradle/compiler-backends.properties` is generated verification data. Its
members are concrete releases verified against one family implementation.
`gradle/compiler-features.properties` records the ordering mechanism proven
by real Compose tests for each compiler, independently of its ABI family.
`gradle/compiler-compatibility-evidence.json` records the compiler source/test
fingerprint, test counts and hashes of adapter implementation classes. Changing
compiler code or its build inputs invalidates release evidence.

Compiler-independent semantic lowering and the model live in
`dootah-compiler-core`. FIR projection, IR interception and symbol handling use
one shared compiler integration in `dootah-compiler-plugin/src/main`. Adapter
source sets contain only compiler API differences; Kotlin 2.4 reuses the modern
adapter source with a distinct annotation-node type bridge. There is no second
copy of the lowering, freezing, identity or propagation algorithms.

## Verifying a new compiler

```sh
# Show stable candidates from Maven Central, without modifying metadata.
python3 tools/compiler_compatibility.py

# Test all stable releases in the supported window with existing adapters.
# Write metadata only when every candidate passes the full suite.
python3 tools/compiler_compatibility.py --verify --promote

# Then run Gradle integration, the real Android Compose check and runtime tests.
./gradlew dootahCompilerMatrixCheck :dootah-contract:test \
  :dootah-android:testDebugUnitTest :dootah-server:test
```

The matrix starts with existing implementations. It tests FIR discovery,
interception, overload and receiver identities, action extraction,
freeze/propagation, subject `when`, adapter-property union, generated bundle
execution, APK/extraction contract agreement, localized edits and real Compose
lowering. A quick probe selects candidates for the entire regression suite; a
quick probe alone cannot certify compatibility. Probe artifacts cannot be
published.

Weekly CI discovers new stable releases automatically. A compatible release
requires generated metadata and verification, not a new source implementation.
An actual compiler API break requires the smallest API bridge, followed by the
same tests. Applications are acceptance tests, not compatibility policies.

Modern families use Kotlin's explicit `-Xcompiler-plugin-order` constraint to
run Dootah before Compose, including when Compose is first on the classpath.
Older compilers lack that facility: Dootah uses the same registrar mechanism as
Compose and requires Dootah before Compose in the plugins block. The IR ordering
guard remains active on every family.

## Before installing a baseline

```sh
./gradlew :app:dootahDoctor
```

Doctor reports the selected variant, actual configured Kotlin compiler, KGP,
AGP, Compose detection, selected family/artifact, embedded ABI metadata,
baseline contract location and runtime version. Missing or incompatible
contracts and unverified compilers report `NOT READY`. Doctor does not compile,
record a new baseline, install an APK or publish anything. Local readiness does
not prove server delivery, signatures or device activation.

If several actual APK variants are ambiguous, select the intended variant with
`dootah.variant` or `-PdootahVariant=...`. The same variant supplies source,
generated code, classpath, resources and contract fragments.

## Incremental contracts

The compiler replaces all entries owned by each recompiled source, including
an empty result after its last screen disappears. Deleted sources are pruned;
recording a baseline also prunes files removed from the selected source set.
Ownership uses project-relative paths so cached build outputs can relocate.
Clean and partial-compilation tests compare semantic contracts after deletion,
renaming, parameter changes and overload changes. Real Gradle consumer tests on
both Kotlin 2.0 and 2.3 also compare incremental and clean/build-cache results,
including an unchanged up-to-date build and deletion of an entire source file.
Users do not need `clean` to remove old capabilities.

This metadata is build state. The OTA wire protocol and `runtimeVersion = 9`
are unchanged. Only `dootahRecordContract` explicitly records a baseline;
publishing an update must continue to validate against the installed baseline.

## Release and acceptance

Publication checks that generated compatibility evidence matches the compiler
implementation. A matrix pass is not device OTA proof. Freeze the implementation
before unrelated external consumer tests, classify failures before considering
changes, and record build, delivery and real-device results separately. Publish
one new immutable version only after the required acceptance work passes.
