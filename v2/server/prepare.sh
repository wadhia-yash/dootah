#!/usr/bin/env bash
set -euo pipefail
upstream=${1:?usage: prepare.sh upstream-checkout new-output-directory}
output=${2:?new output directory required}
revision=b46e13569f5734a66ed903f75f51b87b78e80c1b
test "$(git -C "$upstream" rev-parse HEAD)" = "$revision"
test ! -e "$output"
mkdir -p "$output/cmd/dootah"
# Export committed, licensed core inputs only. No full checkout or commercial source
# enters the build tree, including the unused upstream entrypoint/router/dashboard.
git -C "$upstream" archive "$revision" go.mod go.sum LICENSE.md config internal |
  tar -x -C "$output" --exclude='*/ee' --exclude='*/ee/*' --exclude='internal/router' --exclude='internal/router/*'
cp "$(dirname "$0")/main.go" "$output/cmd/dootah/main.go"
if find "$output" -type d -name ee | grep -q .; then
  echo 'Commercial directory in build input' >&2
  exit 1
fi
