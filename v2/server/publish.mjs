// Thin client for xprem's existing three-step publish API; no bundling or signing.
import { readFile, realpath } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import { createHash } from 'node:crypto';

export async function publish(base, credentials, artifact, { publicOrigin = base } = {}) {
  const root = await realpath(artifact);
  const bytes = new Map();
  async function load(path) {
    const full = await realpath(resolve(root, path));
    if (!full.startsWith(root + sep)) throw new Error('Artifact path escapes export');
    const data = await readFile(full);
    bytes.set(path, data);
    return data;
  }
  const metadata = JSON.parse(await load('metadata.json'));
  const config = JSON.parse(await load('expoConfig.json'));
  if (metadata.version !== 0 || !['metro', 'dootah'].includes(metadata.bundler) || typeof config.runtimeVersion !== 'string') {
    throw new Error('Expected a supported update artifact with an explicit runtimeVersion');
  }
  const android = metadata.fileMetadata.android;
  const input = [
    { path: 'metadata.json', role: 'config' },
    { path: 'expoConfig.json', role: 'config' },
    { path: android.bundle, role: 'launch', ext: metadata.bundler === 'dootah' ? 'json' : 'hbc' },
    ...android.assets.map(a => ({ ...a, role: 'asset' })),
  ];
  const files = [];
  for (const file of input) {
    const data = bytes.get(file.path) ?? await load(file.path);
    files.push({ ...file, hash: createHash('sha256').update(data).digest('base64url'),
      ...(file.role === 'config' ? {} : { key: createHash('md5').update(data).digest('hex') }) });
  }
  const headers = { Authorization: `Bearer ${credentials.apiKey}` };
  const query = new URLSearchParams({ platform: 'android', runtimeVersion: config.runtimeVersion });
  const route = `${base}/${encodeURIComponent(credentials.appId)}`;
  const branch = encodeURIComponent(credentials.branch);
  const request = await fetch(`${route}/requestUploadUrl/${branch}?${query}`, {
    method: 'POST', redirect: 'error', signal: AbortSignal.timeout(20000), headers: { ...headers, 'Content-Type': 'application/json' },
    body: JSON.stringify({ files, message: 'Dootah Phase 1 artifact' }),
  });
  if (!request.ok) throw new Error(`Prepare ${request.status}: ${await request.text()}`);
  const upload = await request.json();
  for (const item of upload.uploadRequests) {
    const data = bytes.get(item.filePath);
    if (!data || item.hash !== createHash('sha256').update(data).digest('base64url') ||
        new URL(item.requestUploadUrl).origin !== new URL(publicOrigin).origin) {
      throw new Error('Unexpected upload file, hash or origin');
    }
    const form = new FormData();
    form.append('file', new Blob([data]), item.filePath);
    const advertised = new URL(item.requestUploadUrl);
    const uploadUrl = new URL(advertised.pathname + advertised.search, base);
    const result = await fetch(uploadUrl, {
      method: 'PUT', redirect: 'error', signal: AbortSignal.timeout(20000), headers: { ...item.headers, ...headers }, body: form,
    });
    if (!result.ok) throw new Error(`Upload ${result.status}: ${await result.text()}`);
  }
  query.set('updateId', String(upload.updateId));
  const sealed = await fetch(`${route}/markUpdateAsUploaded/${branch}?${query}`, {
    method: 'POST', redirect: 'error', signal: AbortSignal.timeout(20000), headers,
  });
  if (!sealed.ok) throw new Error(`Finalize ${sealed.status}: ${await sealed.text()}`);
  return { updateId: upload.updateId, ...await sealed.json(), runtimeVersion: config.runtimeVersion,
    launchHash: files.find(f => f.role === 'launch').hash };
}

if (process.argv[1] && import.meta.url === new URL(`file://${resolve(process.argv[1])}`).href) {
  const [base, credentialFile, artifact] = process.argv.slice(2);
  if (!artifact) throw new Error('Usage: node publish.mjs base-url private-credentials.json export-directory');
  console.log(JSON.stringify(await publish(base, JSON.parse(await readFile(credentialFile)), artifact), null, 2));
}
