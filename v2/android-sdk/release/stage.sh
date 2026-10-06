#!/usr/bin/env bash
set -euo pipefail
sdk_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
# A failed/previous candidate is never silently overwritten. Choose a new output directory.
stage="${1:-$sdk_dir/build/release-stage}"
if [[ -e "$stage" ]]; then echo "Refusing existing stage: $stage" >&2; exit 2; fi
mkdir -p "$stage"
stage="$(cd "$stage" && pwd)"
producer="$sdk_dir/../runtime-spike"
(cd "$producer" && npm ci)
"$producer/android/gradlew" -p "$producer/android" -PdootahSdk -PdootahReleaseStage \
  -PreactNativeArchitectures=arm64-v8a -PdootahRepository="$stage/raw" -PdootahEvidence="$stage/evidence" \
  publishDootahPublicationToDootahRepository dootahDistributionInventory
"$producer/android/gradlew" -p "$sdk_dir/gradle-plugin" -PdootahReleaseStage \
  -PdootahRepository="$stage/raw" -PdootahEvidence="$stage/evidence" publish dootahDistributionInventory
python3 "$sdk_dir/release/package.py" "$stage"
python3 "$sdk_dir/release/validate.py" "$stage"
