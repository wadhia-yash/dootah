// Fails unless every given JUnit XML results directory exists and reports at least one test
// with zero skipped, failed or errored tests. Required suites may never be silently skipped.
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

let failed = false;
for (const dir of process.argv.slice(2)) {
  let files = [];
  try { files = readdirSync(dir).filter((f) => f.startsWith("TEST-") && f.endsWith(".xml")); } catch {}
  const totals = { tests: 0, skipped: 0, failures: 0, errors: 0 };
  for (const file of files) {
    const suite = readFileSync(join(dir, file), "utf8").match(/<testsuite\b[^>]*>/)?.[0] ?? "";
    for (const key of Object.keys(totals)) totals[key] += Number(suite.match(new RegExp(`\\b${key}="(\\d+)"`))?.[1] ?? 0);
  }
  const ok = files.length > 0 && totals.tests > 0 && totals.skipped + totals.failures + totals.errors === 0;
  console.log(`${ok ? "PASS" : "FAIL"} ${dir}: ${files.length} suites, ${totals.tests} tests, ${totals.skipped} skipped, ${totals.failures + totals.errors} failed`);
  failed ||= !ok;
}
if (failed) process.exit(1);
