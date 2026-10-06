#!/usr/bin/env bash
set -euo pipefail
sdk_dir="$(cd "$(dirname "$0")" && pwd)"
producer="$sdk_dir/../runtime-spike"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
# Dootah's producer owns npm/Metro. None of these commands run in a consumer build.
(cd "$producer" && npm ci)
# Required suites must run in this invocation; stale results can never satisfy the gate.
results=("$sdk_dir/runtime/build/test-results/testReleaseUnitTest"
  "$producer/node_modules/expo-updates/android/build/test-results/testReleaseUnitTest"
  "$sdk_dir/gradle-plugin/build/test-results/test")
rm -rf "${results[@]}"
(cd "$producer/android" && ./gradlew -PdootahSdk -PreactNativeArchitectures=arm64-v8a \
  publishDootahPublicationToDootahRepository :runtime-v2:testReleaseUnitTest \
  :expo-updates:testReleaseUnitTest --tests '*NativeHealthTest')
"$producer/android/gradlew" -p "$sdk_dir/gradle-plugin" test publish
node "$sdk_dir/tests/require-tests.mjs" "${results[@]}"
# Phase 8 consumer-compatibility gates on the published runtime artifact.
runtime_version=$(sed -n 's/^dootah\.sdk\.version=//p' "$sdk_dir/version.properties")
node - "$sdk_dir/build/maven/dev/dootah/runtime-v2/$runtime_version" "$runtime_version" <<'JS'
const fs = require('fs'), path = require('path'), { execFileSync } = require('child_process');
const [dir, version] = process.argv.slice(2);
const meta = JSON.parse(fs.readFileSync(path.join(dir, `runtime-v2-${version}.module`), 'utf8'));
const api = meta.variants.find(v => v.attributes['org.gradle.usage'] === 'java-api');
if (!api.dependencies.some(d => d.group === 'com.google.guava' && d.module === 'guava'))
  throw Error('runtime API variant must expose Guava to keep consumer compile/runtime classpaths consistent');
const manifest = execFileSync('unzip', ['-p', path.join(dir, `runtime-v2-${version}.aar`), 'AndroidManifest.xml']).toString();
const requested = [...manifest.matchAll(/<uses-permission\s+android:name="([^"]+)"\s*\/>/g)].map(m => m[1]);
if (JSON.stringify(requested) !== JSON.stringify(['android.permission.INTERNET'])) throw Error(`unexpected runtime permissions ${requested}`);
console.log('Published runtime consumer-compatibility gates passed');
JS
# Fixture-backed instrumentation gate on the artifacts just published; never optional.
"$sdk_dir/tests/compose-hook.sh"
