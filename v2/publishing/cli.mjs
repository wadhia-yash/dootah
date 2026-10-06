#!/usr/bin/env node
import { readFile, writeFile, mkdir, mkdtemp } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { publishCloud } from './cloud.mjs';
import { analyzer } from './tooling.mjs';
import { customer, help } from './customer.mjs';
import { existsSync } from 'node:fs';
import { parseArgs } from 'node:util';

const root = dirname(fileURLToPath(import.meta.url));
const read = async path => JSON.parse(await readFile(path, 'utf8'));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n');
async function main() {
  const [mode, configArg = 'dootah-publish.json'] = process.argv.slice(2);
  if (!mode || mode === '--help' || mode === 'help') { console.log(help); return; }
  if (!['import', 'analyze', 'bundle', 'publish'].includes(mode)) {
    console.log(JSON.stringify(await customer(process.argv.slice(2)), null, 2));
    return;
  }
  const configPath = resolve(configArg);
  const config = await read(configPath);
  const { values: flags } = parseArgs({ args: process.argv.slice(4), options: {
    'token-file': { type: 'string' }, revision: { type: 'string' }, key: { type: 'string' },
  } });
  if (flags['token-file']) { assert(config.cloud, 'Token input requires a Cloud target'); config.cloud.tokenFile = resolve(flags['token-file']); }
  if (flags.revision) config.sourceRevision = flags.revision;
  if (flags.key) { assert(config.cloud, 'Idempotency key requires a Cloud target'); config.cloud.idempotencyKey = flags.key; }
  const base = dirname(configPath);
  for (const name of ['sourceRoot', 'contract', 'apk', 'classes', 'classHashes', 'credentials']) {
    if (config[name]) config[name] = resolve(base, config[name]);
  }
  for (const name of ['tokenFile', 'sessionFile']) {
    if (config.cloud?.[name]) config.cloud[name] = resolve(base, config.cloud[name]);
  }
  const workRoot = resolve(base, config.outputRoot ?? 'build/dootah-publish');
  await mkdir(workRoot, { recursive: true });
  const work = await mkdtemp(join(workRoot, 'run-'));
  const effective = join(work, 'config.json');
  await save(effective, config);
  if (mode === 'import') {
    assert(config.contract, 'Provide an output contract path');
    analyzer(['import', effective, config.contract]);
    const contract = await read(config.contract);
    for (const key of ['apkSha256', 'runtimeVersion', 'packageName', 'appId', 'channel']) config[key] = contract[key];
    config.contractSha256 = hash(await readFile(config.contract));
    await save(configPath, config);
    console.log(`Imported ${contract.functions.length} source/installed identities for ${contract.packageName}, runtime ${contract.runtimeVersion}.`);
    return;
  }
  const contractBytes = await readFile(config.contract);
  assert.equal(hash(contractBytes), config.contractSha256, 'Stale or modified installed contract');
  const contract = JSON.parse(contractBytes);
  assert.equal(hash(await readFile(config.apk)), contract.apkSha256,
    'Retained installed APK changed; import the matching release contract');
  const resultPath = join(work, 'analysis.json');
  analyzer(['analyze', effective, resultPath]);
  const result = await read(resultPath);
  for (const f of result.report) console.log(`${f.status.padEnd(9)} ${f.name}${f.changed ? ' (changed)' : ''}${f.reason ? ': ' + f.reason : ''}`);
  const portable = result.report.filter(f => f.status === 'portable').length;
  console.log(`${result.report.length} analyzed; ${portable} changed OTA-capable; ${result.report.filter(f => f.status === 'native').length} native. Runtime ${contract.runtimeVersion}; channel ${contract.channel}.`);
  await save(join(work, 'portable-ir.json'), result.ir);
  console.log(`Evidence: ${work}`);
  assert(!result.refused, 'A changed function is unsupported. No OTA published; native implementation remains installed.');
  if (mode === 'analyze') return;
  assert.equal(contract.schema, 2, 'Publishing requires the secure ABI 2 baseline APK; ABI 1 has no capability boundary');
  if (!portable) { console.log('No supported changes; no release created.'); return; }
  const generated = {payload: {overrides: result.ir.overrides}, js: JSON.stringify(result.generatedJs)};
  const output = join(work, 'export');
  // Expo transports authenticated IR data. Only the installed compiler creates executable JS.
  await mkdir(output);
  await writeFile(join(output, 'program.json'), JSON.stringify(result.ir));
  await save(join(work, 'generated-js.json'), result.generatedJs);
  await save(join(output, 'metadata.json'), {version: 0, bundler: 'dootah',
    fileMetadata: {android: {bundle: 'program.json', assets: []}}});
  await save(join(output, 'expoConfig.json'), {runtimeVersion: contract.runtimeVersion, android: {package: contract.packageName}});

  assert.equal(hash(await readFile(config.contract)), config.contractSha256, 'Contract changed during export');
  if (mode === 'bundle') { console.log(`Generated update artifact: ${output}`); return; }
  if (config.cloud) {
    const receipt = await publishCloud(config, contract, result.ir);
    await save(join(work, 'receipt.json'), {...receipt, contractSha256: config.contractSha256});
    console.log(`Cloud release ${receipt.releaseId}; operation ${receipt.operationId}; ${receipt.status}. Enable rollout after publication is ready.`);
    return;
  }
  const credentials = await read(config.credentials);
  assert.equal(credentials.appId, contract.appId, 'Publisher app mismatch');
  assert.equal(credentials.channel, contract.channel, 'Publisher channel mismatch');
  const origin = new URL(contract.updateUrl);
  assert(origin.protocol === 'https:' || (config.allowLocalHttp === true &&
    origin.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname)), 'Insecure publish origin');
  const { publish } = await import(existsSync(join(root, 'xprem.mjs')) ? './xprem.mjs' : '../server/publish.mjs');
  const receipt = await publish(origin.origin, credentials, output);
  await save(join(work, 'receipt.json'), { ...receipt, contractSha256: config.contractSha256,
    portableIrSha256: hash(Buffer.from(JSON.stringify(result.ir))),
    jsSha256: hash(Buffer.from(generated.js)), channel: contract.channel, functions: generated.payload.overrides.map(f => f.functionId) });
  console.log(`Published release ${receipt.updateId}; runtime ${receipt.runtimeVersion}; channel ${contract.channel}. Activates on next process launch after download.`);
}

main().catch(error => { console.error(`Dootah refused: ${error.message}`); process.exitCode = 1; });
