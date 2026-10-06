ALTER TABLE dootah_cloud.operations ADD CONSTRAINT operation_tenant_release
 FOREIGN KEY(organization_id,resource_id) REFERENCES dootah_cloud.releases(organization_id,id);
ALTER TABLE dootah_cloud.environments ADD CONSTRAINT environment_tenant_head
 FOREIGN KEY(organization_id,app_id,id,head) REFERENCES dootah_cloud.releases(organization_id,app_id,environment_id,id);
ALTER TABLE dootah_cloud.credentials ADD CONSTRAINT credential_tenant_app
 FOREIGN KEY(organization_id,app_id) REFERENCES dootah_cloud.apps(organization_id,id);
ALTER TABLE dootah_cloud.credentials ADD CONSTRAINT credential_tenant_environment
 FOREIGN KEY(organization_id,app_id,environment_id) REFERENCES dootah_cloud.environments(organization_id,app_id,id);
ALTER TABLE dootah_cloud.credentials ADD CONSTRAINT credential_tenant_installation
 FOREIGN KEY(organization_id,app_id,environment_id,installation_id) REFERENCES dootah_cloud.installations(organization_id,app_id,environment_id,id);
CREATE INDEX events_health ON dootah_cloud.events(organization_id,release_id,installation_id,activation_id,type,occurred_at);
CREATE INDEX releases_environment ON dootah_cloud.releases(organization_id,environment_id,id);
CREATE INDEX operations_pending ON dootah_cloud.operations(created_at) WHERE status IN ('pending','running');
INSERT INTO dootah_cloud.migrations(version) VALUES(5);
