ALTER TABLE dootah_cloud.operations ADD COLUMN attempts integer NOT NULL DEFAULT 0;
ALTER TABLE dootah_cloud.operations ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
DROP INDEX dootah_cloud.operations_pending;
CREATE INDEX operations_pending ON dootah_cloud.operations(next_attempt_at,created_at) WHERE status IN ('pending','running');
INSERT INTO dootah_cloud.migrations(version) VALUES(6);
