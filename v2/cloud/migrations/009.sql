-- Additive only: existing organizations, users, credentials and releases are retained.
ALTER TABLE dootah_cloud.organizations ADD COLUMN hosted_external boolean NOT NULL DEFAULT false;
ALTER TABLE dootah_cloud.organizations ALTER COLUMN hosted_external SET DEFAULT true;
CREATE TABLE dootah_cloud.hosted_controls (
 id boolean PRIMARY KEY DEFAULT true CHECK(id),
 registrations_paused boolean NOT NULL DEFAULT false,
 publications_paused boolean NOT NULL DEFAULT false
);
INSERT INTO dootah_cloud.hosted_controls DEFAULT VALUES;
CREATE TABLE dootah_cloud.invitations (
 id uuid PRIMARY KEY, email text NOT NULL, digest text UNIQUE NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL DEFAULT now()+interval '7 days',
 revoked_at timestamptz, redeemed_at timestamptz, redeemed_by uuid REFERENCES dootah_cloud.users,
 CHECK ((redeemed_at IS NULL) = (redeemed_by IS NULL))
);
CREATE INDEX invitations_pending ON dootah_cloud.invitations(expires_at) WHERE revoked_at IS NULL AND redeemed_at IS NULL;
CREATE INDEX invitations_user ON dootah_cloud.invitations(redeemed_by) WHERE redeemed_by IS NOT NULL;
CREATE INDEX users_email_normalized ON dootah_cloud.users(lower(email));
ALTER TABLE dootah_cloud.operations ADD COLUMN completed_at timestamptz;
-- Historical completion time was not recorded; conservatively start its window now.
UPDATE dootah_cloud.operations SET completed_at=now() WHERE action='publish' AND status='succeeded';
ALTER TABLE dootah_cloud.releases ADD COLUMN size_bytes integer;
UPDATE dootah_cloud.releases SET size_bytes=octet_length(artifact_bytes) WHERE artifact_bytes IS NOT NULL;
CREATE INDEX operations_publish_usage ON dootah_cloud.operations(organization_id,completed_at) WHERE action='publish';
CREATE TABLE dootah_cloud.hosted_usage (
 month date NOT NULL, organization_id uuid NOT NULL,
 requests bigint NOT NULL DEFAULT 0, update_checks bigint NOT NULL DEFAULT 0,
 estimated_bytes bigint NOT NULL DEFAULT 0, PRIMARY KEY(month,organization_id)
);
GRANT SELECT ON dootah_cloud.invitations, dootah_cloud.hosted_controls, dootah_cloud.hosted_usage TO dootah_cloud_runtime;
GRANT UPDATE(redeemed_at,redeemed_by) ON dootah_cloud.invitations TO dootah_cloud_runtime;
-- SELECT FOR SHARE/UPDATE needs an UPDATE privilege; runtime cannot change pause flags.
GRANT UPDATE(id) ON dootah_cloud.hosted_controls TO dootah_cloud_runtime;
GRANT INSERT,UPDATE ON dootah_cloud.hosted_usage TO dootah_cloud_runtime;
INSERT INTO dootah_cloud.migrations(version) VALUES(9);
