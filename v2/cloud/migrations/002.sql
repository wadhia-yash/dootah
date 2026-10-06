-- Operate with a separate non-owner login, configured by the deployment operator.
-- That login is granted membership in this NOLOGIN role after migration.
-- Prerequisite: the cluster operator creates dootah_cloud_runtime NOLOGIN.
GRANT USAGE ON SCHEMA dootah_cloud TO dootah_cloud_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA dootah_cloud TO dootah_cloud_runtime;
GRANT INSERT, UPDATE, DELETE ON dootah_cloud.organizations, dootah_cloud.members,
 dootah_cloud.credentials, dootah_cloud.apps, dootah_cloud.environments,
 dootah_cloud.releases, dootah_cloud.installations, dootah_cloud.events,
 dootah_cloud.operations, dootah_cloud.grants, dootah_cloud.rate_limits TO dootah_cloud_runtime;
GRANT INSERT ON dootah_cloud.audit TO dootah_cloud_runtime;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA dootah_cloud TO dootah_cloud_runtime;
INSERT INTO dootah_cloud.migrations(version) VALUES(2);
