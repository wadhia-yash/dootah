#!/usr/bin/env bash
set -euo pipefail
sdk_dir="$(cd "$(dirname "$0")/.." && pwd)"
consumer="$sdk_dir/../native-consumer"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
# Requires the SDK published from this checkout (version.properties). Normal compilation supplies
# the real fixtures; composeFixtureTest fails rather than skips without them.
"$consumer/gradlew" -p "$consumer" :app:compileReleaseKotlin
rm -rf "$sdk_dir/gradle-plugin/build/test-results/composeFixtureTest"
"$consumer/gradlew" -p "$sdk_dir/gradle-plugin" composeFixtureTest \
  -PcomposeFixtures="$consumer/app/build/tmp/kotlin-classes/release"
node "$sdk_dir/tests/require-tests.mjs" "$sdk_dir/gradle-plugin/build/test-results/composeFixtureTest"
# The fixture sets dootah.requireHooks, so a variant with zero entry points fails here.
"$consumer/gradlew" -p "$consumer" :app:assembleDebug :app:assembleRelease --configuration-cache
for variant in debug release; do
  node -e 'const r = require(process.argv[1]); if (!(r.hooks > 0)) throw Error(`no hooks in ${r.variant}`);
    console.log(`fixture ${r.variant}: ${r.hooks} OTA entry points (SDK ${r.sdkVersion})`)' \
    "$consumer/app/build/outputs/dootah/$variant/hooks.json"
done
