import { cp, mkdir, rename, writeFile } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { getConfig } = require('@expo/config');
const project = dirname(fileURLToPath(import.meta.url));
const [serverPath, title] = process.argv.slice(2);
if (!serverPath || !title) {
  throw new Error('Usage: node publish.mjs <upstream expo-updates-server directory> <title|--rollback>');
}

const { exp } = getConfig(project, { skipSDKVersionRequirement: true });
const server = resolve(serverPath);
const parent = join(server, 'updates', exp.runtimeVersion);
const release = Date.now().toString();
// Prepare outside the server's runtime directory, then make the complete release visible.
const staging = join(server, `.dootah-stage-${release}`);
await mkdir(staging, { recursive: false });

if (title === '--rollback') {
  await writeFile(join(staging, 'rollback'), '');
} else {
  execFileSync(process.execPath, [join(project, 'node_modules/expo/bin/cli'),
    'export', '--platform', 'android'], {
    cwd: project,
    stdio: 'inherit',
    env: { ...process.env, CI: '1', EXPO_NO_TELEMETRY: '1', EXPO_PUBLIC_DOOTAH_TITLE: title },
  });
  await cp(join(project, 'dist'), staging, { recursive: true });
  await writeFile(join(staging, 'expoConfig.json'), JSON.stringify(exp));
}
await mkdir(parent, { recursive: true });
const destination = join(parent, release);
await rename(staging, destination);
console.log(`Published upstream fixture release: ${destination}`);
