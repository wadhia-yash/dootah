#!/usr/bin/env bash
#
# What Dootah costs an app that adopts it.
#
# Three builds of the same app, each from clean, each timed and weighed:
#
#   none      the app's own source, no Dootah plugin and no Dootah runtime
#   idle      Dootah applied with `discovery = "annotated"`, so the runtime
#             ships and the compiler runs but nothing is intercepted
#   default   Dootah applied as a developer would leave it: every eligible
#             composable intercepted
#
# `none` to `idle` is the price of the library. `idle` to `default` is the
# price of interception itself, which is the number worth watching as coverage
# grows. Reported as dex bytes rather than APK bytes because an APK is mostly
# resources and compression, and neither moves.
#
# Usage: measure-overhead.sh <app directory> [gradle module]

set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
STRIP="$HERE/strip-dootah.py"

APP_DIR=${1:?app directory}
MODULE=${2:-:app}
APP_NAME=$(basename "$APP_DIR")

export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME

cd "$APP_DIR" || exit 1

BUILD_FILE=app/build.gradle.kts
APK=app/build/outputs/apk/debug/app-debug.apk

# Every mode is a temporary edit of the app's own files, restored from copies
# rather than from git -- the Dootah integration these apps carry is not
# committed, and checking the files out would take it away with the edit.
ORIGINAL=$(mktemp)
cp "$BUILD_FILE" "$ORIGINAL"

INIT_FILE=$(grep -rl "Dootah.initialize" app/src/main --include="*.kt" 2>/dev/null | head -1)
INIT_ORIGINAL=$(mktemp)
[ -n "$INIT_FILE" ] && cp "$INIT_FILE" "$INIT_ORIGINAL"

restore() {
    cp "$ORIGINAL" "$BUILD_FILE"
    [ -n "$INIT_FILE" ] && cp "$INIT_ORIGINAL" "$INIT_FILE"
    return 0
}
trap 'restore; rm -f "$ORIGINAL" "$INIT_ORIGINAL"' EXIT

dex_bytes() {
    unzip -l "$APK" 2>/dev/null |
        awk '$4 ~ /^classes[0-9]*\.dex$/ { total += $1 } END { print total + 0 }'
}

measure() {
    local mode=$1

    restore
    case "$mode" in
        none)
            python3 "$STRIP" build "$BUILD_FILE"
            [ -n "$INIT_FILE" ] && python3 "$STRIP" init "$INIT_FILE"
            ;;
        idle)
            python3 "$STRIP" idle "$BUILD_FILE"
            ;;
        default) ;;
    esac

    ./gradlew "$MODULE:clean" -q --no-build-cache >/dev/null 2>&1

    local start seconds status
    start=$(date +%s)
    ./gradlew "$MODULE:assembleDebug" -q --no-build-cache >/dev/null 2>&1
    status=$?
    seconds=$(( $(date +%s) - start ))

    if [ $status -ne 0 ]; then
        echo "$APP_NAME,$mode,FAILED,"
        return
    fi

    echo "$APP_NAME,$mode,$(dex_bytes),$seconds"
}

echo "app,mode,dexBytes,buildSeconds"
for mode in none idle default; do
    measure "$mode"
done
