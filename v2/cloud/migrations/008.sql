-- Self-service registration may create users, but may not modify or delete them.
GRANT INSERT ON dootah_cloud.users TO dootah_cloud_runtime;
INSERT INTO dootah_cloud.migrations(version) VALUES(8);
