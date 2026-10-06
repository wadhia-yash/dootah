ALTER TABLE dootah_cloud.releases ADD COLUMN artifact_bytes text;
INSERT INTO dootah_cloud.migrations(version) VALUES(3);
