import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { generate, capability } from './portable.mjs';

const id = 'dth1:' + 'a'.repeat(64);
const contract = { irAbi: 1, dispatchAbi: 1, runtimeVersion: 'test', apkSha256: 'apk',
  capabilities: [capability], functions: [{ id, installed: true }] };
const input = () => ({ schema: 'dootah.portable', abi: 1, runtimeVersion: 'test', apkSha256: 'apk',
  overrides: [{ functionId: id, requires: [capability], tree: { type: 'text', value: { type: 'string', value: 'Hello' } } }] });

test('deterministic JS sends only semantic data, with escaped strings', () => {
  const ir = input();
  ir.overrides[0].tree.value.value = '"; throw new Error("injected"); //\n💡';
  const { js, payload } = generate(ir, contract);
  assert.equal(js, generate(ir, contract).js);
  let sent;
  vm.runInNewContext(js.replace("import { sendMessage } from 'expo-brownfield';", ''), { sendMessage: p => sent = JSON.parse(JSON.stringify(p)) });
  assert.deepEqual(sent, payload);
});

test('unknown identities, capabilities, schema, ABI, values and stale APK fail closed', () => {
  const mutations = [
    ir => ir.abi = 2, ir => ir.runtimeVersion = 'wrong', ir => ir.apkSha256 = 'stale',
    ir => ir.overrides[0].functionId = 'dth1:' + 'b'.repeat(64),
    ir => ir.overrides[0].requires = ['arbitrary.android'],
    ir => ir.overrides[0].tree.type = 'layout',
    ir => ir.overrides[0].tree.value.value = { android: 'Context' },
    ir => ir.overrides[0].tree.value.value = '\ud800',
    ir => ir.overrides[0].tree.value.value = ' ',
    ir => ir.overrides[0].tree.value.value = 'x'.repeat(257),
    ir => ir.overrides.push(ir.overrides[0]),
    ir => ir.overrides = [], ir => ir.extra = true,
  ];
  for (const mutate of mutations) { const ir = input(); mutate(ir); assert.throws(() => generate(ir, contract)); }
  assert.throws(() => generate(input(), { ...contract, dispatchAbi: 2 }));
  assert.throws(() => generate(input(), { ...contract, functions: [{ id, installed: false }] }));
});
