#!/usr/bin/env bash
set -euo pipefail
# Every build exercises private host inputs; the images normalize their own
# immutable payload permissions, without relaxing operator secret permissions.
umask 077
# Only allowlisted, non-secret inputs enter either Docker context.
upstream=${1:?usage: build.sh clean-pinned-xprem-checkout}
root=$(cd "$(dirname "$0")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
bash "$root/v2/server/prepare.sh" "$upstream" "$work/xprem"
bash "$root/v2/server/build.sh" "$work/xprem" "$upstream"
python3 - "$work/xprem/upstream-tests.json" <<'PYTEST'
import json, sys
items = [json.loads(line) for line in open(sys.argv[1])]
skipped = [i['Test'] for i in items if i.get('Test') and i['Action'] == 'skip']
expected = {'TestAzuriteUploadListGet', 'TestAzuriteSASUploadRequiresBlockBlobHeader', 'TestAzuriteCreateFrom', 'TestAzuriteDeleteUpdate'}
assert set(skipped) == expected, 'unexpected skipped upstream tests'
assert not any(i['Action'] == 'fail' for i in items), 'upstream test failure'
passed = sum(i.get('Test') is not None and i['Action'] == 'pass' for i in items)
print(f'xprem: {passed} passed, 0 failed; 4 Azure-only tests explicitly outside local-storage deployment: ' + ', '.join(skipped))
PYTEST
if [ -n "${DOOTAH_BUILD_EVIDENCE:-}" ]; then
  mkdir -p "$DOOTAH_BUILD_EVIDENCE"
  cp "$work/xprem/audit.json" "$work/xprem/upstream-tests.json" "$DOOTAH_BUILD_EVIDENCE/"
fi
cp "$root/v2/deploy/Xprem.Dockerfile" "$work/xprem/Dockerfile"
cp "$root/v2/deploy/entrypoint.sh" "$work/xprem/entrypoint.sh"
mkdir -p "$work/xprem/notices"
cp "$root/docs/v2/evidence/phase2b-20260926/licenses.csv" "$root/v2/server/XPREM-LICENSE.md" "$root/LICENSE" "$work/xprem/notices/"
# Keep audit output available separately; never ship it or a full upstream checkout.
rm -f "$work/xprem/dootah-server" "$work/xprem/"*.json "$work/xprem/"*.txt
# go.mod/go.sum are retained; package-lock is in the other context.
docker build -t dootah-xprem:9d "$work/xprem"
mkdir -p "$work/cloud/cloud" "$work/cloud/server" "$work/cloud/validator" "$work/cloud/notices"
cp "$root/LICENSE" "$work/cloud/notices/"
for file in adapter.mjs admin.mjs client.mjs config.mjs core.mjs server.mjs worker.mjs package.json package-lock.json dashboard.html dashboard.css dashboard.js; do
  cp "$root/v2/cloud/$file" "$work/cloud/cloud/"
done
mkdir -p "$work/cloud/cloud/migrations" "$work/cloud/cloud/test"
cp "$root/v2/cloud/migrations/"*.sql "$work/cloud/cloud/migrations/"
cp "$root/v2/cloud/test/"*.test.mjs "$work/cloud/cloud/test/"
cp "$root/v2/server/publish.mjs" "$work/cloud/server/"
cp "$root/v2/cloud/Validate.java" "$root/v2/portable/src/main/java/dev/dootah/portable/PortableProgram.java" "$work/cloud/validator/"
curl --fail --silent --show-error --location https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar -o "$work/cloud/validator/json.jar"
python3 - "$work/cloud/validator/json.jar" <<'PY'
import hashlib,sys
assert hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest() == '3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796'
PY
cp "$root/v2/deploy/Cloud.Dockerfile" "$work/cloud/Dockerfile"
cp "$root/v2/deploy/entrypoint.sh" "$work/cloud/entrypoint.sh"
docker build -t dootah-cloud:9d "$work/cloud"
python3 "$root/v2/deploy/image_permissions.py"
