#!/usr/bin/env bash
# JitPack install step. Installs the release's pre-staged Maven tree (built and validated
# by stage.sh, re-coordinated by jitpack.py) after checking it against the digest committed
# alongside this script. JitPack does not rebuild the native SDK.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tag="${VERSION:-$(git describe --tags --exact-match HEAD)}"
expected="$(sed -n "s/^\([0-9a-f]\{64\}\)  dootah-${tag}-jitpack-maven\.zip$/\1/p" "$here/jitpack.sha256")"
[ -n "$expected" ] || { echo "no committed digest for $tag" >&2; exit 1; }
work="$(mktemp -d)"
curl --fail --silent --show-error --location -o "$work/maven.zip" \
  "https://github.com/wadhia-yash/dootah/releases/download/${tag}/dootah-${tag}-jitpack-maven.zip"
actual="$(sha256sum "$work/maven.zip" | cut -d' ' -f1)"
[ "$actual" = "$expected" ] || { echo "release asset digest mismatch" >&2; exit 1; }
mkdir -p "$HOME/.m2/repository" build/jitpack
unzip -q -o "$work/maven.zip" -d "$HOME/.m2/repository"
unzip -q -o "$work/maven.zip" -d build/jitpack
find "$HOME/.m2/repository/com/github/wadhia-yash/dootah" -name '*.pom' | sed 's#.*/repository/##'
