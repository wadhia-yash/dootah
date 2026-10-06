import { test, after } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import {
  bucket,
  eligible,
  pool,
  password,
  checkPassword,
  permit,
} from "../core.mjs";
after(() => pool.end());
test("rollout is deterministic, monotonic and bounded for all supported percentages", () => {
  const release = "00000000-0000-4000-8000-000000000001";
  for (let n = 0; n < 2000; n++) {
    const id = randomUUID();
    const b = bucket(id, release);
    assert.equal(bucket(id, release), b);
    assert(b >= 0 && b < 10000);
    let previous = false;
    for (const pct of [0, 1, 10, 25, 50, 100]) {
      const value = eligible(id, release, pct);
      if (previous) assert(value);
      previous = value;
    }
    assert(!eligible(id, release, 0));
    assert(eligible(id, release, 100));
  }
  assert.throws(() => bucket("", release));
  assert.notEqual(
    bucket(release, release),
    bucket(release, "00000000-0000-4000-8000-000000000002"),
  );
});
test("password hashing uses salt and verifies exact bytes", () => {
  const one = password("a long password");
  assert.notEqual(one, password("a long password"));
  assert(checkPassword("a long password", one));
  assert(!checkPassword("wrong", one));
});
test("viewer cannot mutate even with a mutation scope", () => {
  assert.throws(() =>
    permit(
      { user_id: "u", role: "Viewer", scopes: ["release:publish"] },
      "release:publish",
    ),
  );
  permit(
    { user_id: "u", role: "Viewer", scopes: ["telemetry:read"] },
    "telemetry:read",
  );
});
