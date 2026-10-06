// These tests reproduce an UNSAFE Phase 5 host; passing is NOT an isolation pass.
// Set DOOTAH_EXPOSURE_LOG to test fresh physical-device logcat output.
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import test from 'node:test';

let records;
if (process.env.DOOTAH_EXPOSURE_LOG) {
  records = {};
  for (const line of readFileSync(process.env.DOOTAH_EXPOSURE_LOG, 'utf8').split('\n')) {
    const marker = line.indexOf('DOOTAH_EXPOSURE ');
    if (marker < 0) continue;
    const { name, value } = JSON.parse(line.slice(marker + 'DOOTAH_EXPOSURE '.length));
    assert(!Object.hasOwn(records, name), `Duplicate probe record: ${name}`);
    records[name] = value;
  }
} else {
  records = JSON.parse(readFileSync(new URL(
    '../../../../docs/v2/evidence/phase6-20260928/exposure.json', import.meta.url), 'utf8'));
}
const result = name => {
  assert.equal(records[name]?.ok, true, `${name} must actually execute`);
  return records[name].result;
};

test('physical probe is complete; no missing or fatal records', () => {
  assert.equal(records.complete, true);
  assert.equal(records.fatal, undefined);
  assert.equal(Object.keys(records).length, 73);
  assert(records.globalNames.includes('nativeModuleProxy'));
});
test('UNSAFE: installed RN modules reachable through all three lookup paths', () => {
  for (const name of ['Networking', 'Clipboard', 'IntentAndroid', 'PermissionsAndroid', 'DeviceInfo']) {
    assert.deepEqual(result('native.' + name), {
      nativeModules: true, turboRegistry: true, directGlobal: true,
    });
  }
});
test('UNSAFE: Expo registry directly exposes filesystem, fetch, updates and shared state', () => {
  for (const name of ['ExponentFileSystem', 'FileSystem', 'ExpoFetchModule',
    'ExpoUpdates', 'ExpoBrownfieldStateModule']) {
    assert.equal(result('expo.' + name).available, true);
    assert.equal(result('expo.' + name).directGlobal, true);
  }
});
test('UNSAFE: JavaScript cannot delete or replace either native registry global', () => {
  for (const name of ['nativeModuleProxy', 'expo']) {
    assert.equal(result('global.' + name).configurable, false);
    assert.equal(result('global.' + name).writable, false);
  }
});
test('UNSAFE: eval, Function, constructor native access and bundled dynamic import execute', () => {
  for (const name of ['eval', 'function', 'constructorNative', 'import']) {
    assert.equal(result('dynamic.' + name), true);
  }
});
test('UNSAFE: actual network request and cache file write/read/delete complete', () => {
  assert.equal(result('network.loopback'), true);
  assert.equal(result('filesystem.cacheRoundTrip'), true);
});
test('UNSAFE: uncontrolled timer, clock, randomness and performance clock available', () => {
  assert.equal(result('async.timer'), true);
  assert.deepEqual(result('nondeterminism'), { clock: true, random: true, performance: true });
});
test('missing module names and absent JVM globals are not an allowlist', () => {
  assert.deepEqual(result('native.DootahUnknownOperation'), {
    nativeModules: false, turboRegistry: false, directGlobal: false,
  });
  assert.equal(result('expo.DootahUnknownCapability').available, false);
  for (const name of ['Context', 'Activity', 'Java', 'Packages', 'localStorage', 'indexedDB']) {
    assert.equal(result('global.' + name).type, 'undefined');
  }
});
