#!/usr/bin/env bash
set -euo pipefail
source_dir=${1:?usage: build.sh prepared-source-directory upstream-checkout}
# The audit always compares the build input with the pinned, clean upstream checkout.
upstream=${2:?usage: build.sh prepared-source-directory upstream-checkout}
test -d "$upstream/.git" || { echo "Not an upstream git checkout: $upstream" >&2; exit 2; }
toolchain=golang@sha256:8ac98ca534ac3f51e1f420a1dd2c15e74c75cfa0f23f3ad27eb5d7236c349a0c
# CI supplies unique, empty volumes and removes them on every exit.
docker run --rm \
  -v "$source_dir:/src" \
  -v "${DOOTAH_GO_MOD_VOLUME:-dootah-v2-go-mod}:/go/pkg/mod" \
  -v "${DOOTAH_GO_BUILD_VOLUME:-dootah-v2-go-build}:/root/.cache/go-build" \
  -w /src "$toolchain" sh -ec '
    export CGO_ENABLED=0 GOFLAGS=-mod=readonly
    go version
    go list -deps -json ./cmd/dootah > deps.json
    go list -deps -test -json ./internal/services ./internal/handlers ./internal/crypto ./internal/rollout ./internal/bucket/... > test-deps.json
    go mod graph > module-graph.txt
    go mod verify
    go build -trimpath -o dootah-server ./cmd/dootah
    go version -m dootah-server > binary-modules.txt
    go test -json ./internal/services ./internal/handlers ./internal/crypto ./internal/rollout ./internal/bucket/... > upstream-tests.json
  '
python3 "$(dirname "$0")/audit.py" "$source_dir" "$upstream" > "$source_dir/audit.json"
