import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = dirname(fileURLToPath(import.meta.url));
export function analyzer(args) {
  const installed = join(root, '../bin/dootah-publishing');
  const command = existsSync(installed) ? installed : join(root, 'build/install/dootah-publishing/bin/dootah-publishing');
  if (!existsSync(command)) throw Error('Build the Dootah tool once with ./gradlew installDist in its source directory, then use build/install/dootah-publishing/bin/dootah');
  const result = spawnSync(command, args, { stdio: 'inherit' });
  if (result.error || result.status !== 0) throw Error('Dootah analyzer refused the inputs');
}
