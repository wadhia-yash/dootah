// Assertions over curated physical Android evidence; this is not a Node sandbox claim.
import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import test from 'node:test';
const proof = JSON.parse(readFileSync(new URL('../../../../docs/v2/evidence/phase6-20260928/sandbox-proof.json', import.meta.url)));
test('physical separate sandbox blocks tested native registries and APIs', () => {
  assert.equal(proof.status, 'PASS');
  assert.equal(proof.remoteResult, '80');
  assert(Object.keys(proof.blockedGlobals).length >= 24);
  for (const type of Object.values(proof.blockedGlobals)) assert.equal(type, 'undefined');
  assert.equal(Object.keys(proof.nativeAttempts).length, 8);
  for (const result of Object.values(proof.nativeAttempts)) assert.equal(result, 'EvaluationFailedException');
  assert.equal(proof.unknownNamedData, 'BLOCKED');
});
test('language dynamism does not grant native capabilities', () => {
  assert.equal(proof.eval, '3');
  assert.equal(proof.Function, '3');
  assert.equal(proof.constructorNative, 'undefined');
  assert.equal(proof.import, 'BLOCKED');
});
test('physical limits terminate work and the application recovers', () => {
  assert.equal(proof.timeout, 'terminated');
  assert.equal(proof.afterTimeout, 'PASS');
  assert.equal(proof.resultLimit, 'EvaluationResultSizeLimitExceededException');
  assert.equal(proof.heapLimit, 'MemoryLimitExceededException');
  assert.equal(proof.afterHeap, 'PASS');
  assert.equal(proof.freshIsolate, 'PASS');
  assert.equal(proof.portable.rejections, 17);
  assert.equal(proof.portable.result, '80');
});
