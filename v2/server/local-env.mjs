// Creates private local-only configuration once; never rotates existing keys.
import { mkdir, writeFile } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import { resolve } from 'node:path';
const state = resolve(process.argv[2] ?? (() => { throw new Error('Supply external state directory'); })());
await mkdir(state, { recursive: true, mode: 0o700 });
await mkdir(`${state}/assets`, { recursive: true, mode: 0o700 });
const db = process.env.DB_URL;
if (!db) throw new Error('Set DB_URL for the existing database');
await writeFile(`${state}/server.env`, [
  `DB_URL=${db}`, 'STORAGE_MODE=local', 'LOCAL_BUCKET_BASE_PATH=/state/assets',
  'BASE_URL=http://127.0.0.1:3100', 'BIND_TO_ADDRESS=0.0.0.0', 'PORT=3100',
  `DB_KEYS_MASTER_KEY_B64=${randomBytes(32).toString('base64')}`,
  `JWT_SECRET=${randomBytes(32).toString('hex')}`,
  'ADMIN_EMAIL=phase2b@dootah.local', `ADMIN_PASSWORD=Aa1!${randomBytes(24).toString('hex')}`,
  'DISABLE_TELEMETRY=true', 'DISABLE_DEVICE_TELEMETRY=true', 'BUNDLE_DIFFING=false',
  'SKIP_LEGACY_APP_ID_FALLBACK=true', '',
].join('\n'), { mode: 0o600, flag: 'wx' });
console.log(`Private configuration created: ${state}/server.env`);
