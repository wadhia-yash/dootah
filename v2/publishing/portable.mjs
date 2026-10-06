import assert from 'node:assert/strict';

export const capability = 'compose.basic-text.constant.v1';
const keys = (value, expected) => {
  assert(value && typeof value === 'object' && !Array.isArray(value), 'Expected object');
  assert.deepEqual(Object.keys(value).sort(), [...expected].sort(), 'Unexpected payload fields');
};

export function generate(ir, contract) {
  keys(ir, ['schema', 'abi', 'runtimeVersion', 'apkSha256', 'overrides']);
  assert.equal(ir.schema, 'dootah.portable');
  assert.equal(ir.abi, 1, 'Portable IR ABI mismatch');
  assert.equal(contract.irAbi, 1);
  assert.equal(contract.dispatchAbi, 1);
  assert.equal(ir.runtimeVersion, contract.runtimeVersion, 'Runtime mismatch');
  assert.equal(ir.apkSha256, contract.apkSha256, 'Stale APK contract');
  assert.deepEqual(contract.capabilities, [capability]);
  assert(Array.isArray(ir.overrides) && ir.overrides.length >= 1 && ir.overrides.length <= 32,
    'Expected 1–32 changed supported functions');
  const seen = new Set();
  const overrides = ir.overrides.map(entry => {
    keys(entry, ['functionId', 'requires', 'tree']);
    assert(/^dth1:[0-9a-f]{64}$/.test(entry.functionId), 'Malformed function ID');
    assert(!seen.has(entry.functionId), 'Duplicate function');
    seen.add(entry.functionId);
    assert(contract.functions.some(f => f.installed && f.id === entry.functionId), 'Unknown installed function');
    assert.deepEqual(entry.requires, [capability]);
    keys(entry.tree, ['type', 'value']);
    assert.equal(entry.tree.type, 'text');
    keys(entry.tree.value, ['type', 'value']);
    assert.equal(entry.tree.value.type, 'string');
    const title = entry.tree.value.value;
    assert(typeof title === 'string' && title.trim() && title.length <= 256, 'Invalid constant text');
    assert(!/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(title), 'Unpaired surrogate');
    return { functionId: entry.functionId, title };
  }).sort((a, b) => a.functionId.localeCompare(b.functionId));
  const payload = { type: 'dootah.dispatch.v1', abi: 1, overrides };
  const js = `import { sendMessage } from 'expo-brownfield';\nsendMessage(${JSON.stringify(payload)});\n`;
  assert(Buffer.byteLength(js) <= 65536, 'Generated payload exceeds bound');
  return { payload, js };
}
