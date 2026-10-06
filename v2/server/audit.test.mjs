// E2E: build.sh ran the license/EE audit without the upstream checkout, skipping the
// unchanged-upstream comparison. Both entry points must now refuse to run without it.
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const here = new URL(".", import.meta.url).pathname;
const prepared = mkdtempSync(join(tmpdir(), "dootah-audit-"));

test("audit.py requires the upstream checkout", () => {
  const run = spawnSync("python3", [join(here, "audit.py"), prepared], { encoding: "utf8" });
  assert.notEqual(run.status, 0);
  assert.match(run.stderr, /usage: audit\.py prepared-source-directory upstream-checkout/);
});

test("build.sh requires a git upstream checkout before building", () => {
  const missing = spawnSync("bash", [join(here, "build.sh"), prepared], { encoding: "utf8" });
  assert.notEqual(missing.status, 0);
  assert.match(missing.stderr, /upstream-checkout/);
  const notGit = spawnSync("bash", [join(here, "build.sh"), prepared, prepared], { encoding: "utf8" });
  assert.equal(notGit.status, 2);
  assert.match(notGit.stderr, /Not an upstream git checkout/);
});
