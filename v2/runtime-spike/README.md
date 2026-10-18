# Runtime producer workspace

This directory is maintainer infrastructure for the pinned Expo/React Native producer.
Native app consumers should follow the [quickstart](../../docs/getting-started/quickstart.md),
not configure an Expo application or use this workspace as a starter app.

Use Node 24, JDK 21 and the Android tooling in [CONTRIBUTING](../../CONTRIBUTING.md).
The supported SDK build entrypoint is `v2/android-sdk/build-sdk.sh`; it builds the
producer dependencies, applies the maintained patches and runs the required checks.
See [patch maintenance](patches/README.md) before changing upstream dependencies.

Runtime compatibility, sandbox boundaries and native fallback are documented in
[architecture](../../docs/concepts/architecture.md),
[security](../../docs/security/README.md) and
[compatibility](../../docs/reference/support.md). Build outputs are not device OTA proof.
