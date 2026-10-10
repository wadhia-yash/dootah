-- Preserve existing ABI 2 releases and permit validated ABI 3 publications.
ALTER TABLE dootah_cloud.releases DROP CONSTRAINT releases_runtime_abi_check;
ALTER TABLE dootah_cloud.releases ADD CONSTRAINT releases_runtime_abi_check
 CHECK (runtime_abi IN (2, 3));
INSERT INTO dootah_cloud.migrations(version) VALUES(7);
