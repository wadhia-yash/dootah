import assert from "node:assert/strict";
import { client } from "./auth.mjs";
export async function publishCloud(config, contract, artifact) {
  const request = await client({...config.cloud, allowLocalHttp: config.allowLocalHttp});
  const environment = await request(`/v1/environments/${encodeURIComponent(config.cloud.environmentId)}`);
  assert.equal(
    environment.app_id,
    contract.appId,
    "Installed app differs from Cloud app",
  );
  assert.equal(
    environment.name,
    contract.channel,
    "Installed channel differs from Cloud environment",
  );
  assert.equal(
    environment.runtime,
    contract.runtimeVersion,
    "Installed runtime differs from Cloud environment",
  );
  const op = await request('/v1/releases', 'POST', {
    environmentId: environment.id,
    revision: config.sourceRevision ?? 'local',
    artifact,
  }, { 'Idempotency-Key': config.cloud.idempotencyKey ?? crypto.randomUUID() });
  return {
    releaseId: op.resource_id,
    operationId: op.id,
    status: op.status,
    runtimeVersion: contract.runtimeVersion,
  };
}
