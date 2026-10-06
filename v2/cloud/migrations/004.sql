CREATE FUNCTION dootah_cloud.rollout_bucket(installation uuid, release uuid) RETURNS integer
LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT mod(sum(get_byte(sha256(convert_to('dootah-rollout-v1:' || release::text || ':' || installation::text,'UTF8')),i)::numeric * power(256::numeric,7-i)),10000)::integer
 FROM generate_series(0,7) AS i
$$;
ALTER TABLE dootah_cloud.releases ADD COLUMN contract_digest text;
UPDATE dootah_cloud.releases r SET contract_digest=encode(sha256(convert_to(e.contract::text,'UTF8')),'hex')
 FROM dootah_cloud.environments e WHERE e.organization_id=r.organization_id AND e.id=r.environment_id;
ALTER TABLE dootah_cloud.releases ALTER COLUMN contract_digest SET NOT NULL;
INSERT INTO dootah_cloud.migrations(version) VALUES(4);
