ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action CHECK (action IN (
    'TENANT_CREATED', 'TENANT_RENAMED', 'TENANT_ADMIN_LINKED',
    'SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED',
    'SCHEDULE_UPDATED', 'AVAILABILITY_POLICY_UPDATED',
    'SCHEDULE_EXCEPTION_CREATED', 'SCHEDULE_EXCEPTION_UPDATED', 'SCHEDULE_EXCEPTION_REMOVED',
    'BLOCKED_PERIOD_CREATED', 'BLOCKED_PERIOD_CANCELLED',
    'PUBLICATION_PUBLISHED', 'PUBLICATION_UNPUBLISHED', 'PUBLIC_PROFILE_UPDATED'
));

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action_actor;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action_actor CHECK (
    (action = 'TENANT_CREATED' AND actor_type = 'SYSTEM') OR
    (action = 'TENANT_ADMIN_LINKED' AND actor_type = 'SYSTEM' AND actor_ref = 'CONTROLLED_DEMO_PROVISIONER') OR
    (action = 'TENANT_RENAMED' AND actor_type = 'ADMIN') OR
    (action IN (
        'SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED',
        'SCHEDULE_UPDATED', 'AVAILABILITY_POLICY_UPDATED',
        'SCHEDULE_EXCEPTION_CREATED', 'SCHEDULE_EXCEPTION_UPDATED', 'SCHEDULE_EXCEPTION_REMOVED',
        'BLOCKED_PERIOD_CREATED', 'BLOCKED_PERIOD_CANCELLED',
        'PUBLICATION_PUBLISHED', 'PUBLICATION_UNPUBLISHED', 'PUBLIC_PROFILE_UPDATED'
    ) AND actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN')
);

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_details_allowlist;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_details_allowlist CHECK (
    (action = 'TENANT_CREATED' AND details = '{"changed_fields":["name","slug","timezone"]}'::jsonb) OR
    (action = 'TENANT_RENAMED' AND details->'changed_fields' = '["name"]'::jsonb
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb) OR
    (action = 'TENANT_ADMIN_LINKED' AND details = '{"changed_fields":["identity","membership"]}'::jsonb) OR
    (action = 'SERVICE_CREATED' AND details = '{"changed_fields":["name","description","duration_minutes","buffer_after_minutes","status"]}'::jsonb) OR
    (action = 'SERVICE_UPDATED' AND jsonb_typeof(details->'changed_fields') = 'array'
        AND jsonb_array_length(details->'changed_fields') > 0
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb
        AND NOT jsonb_path_exists(details->'changed_fields', '$[*] ? (@.type() != "string" || (@ != "name" && @ != "description" && @ != "duration_minutes" && @ != "buffer_after_minutes"))')) OR
    (action IN ('SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED') AND details = '{"changed_fields":["status"]}'::jsonb) OR
    (action = 'SCHEDULE_UPDATED' AND details->'changed_fields' = '["weekly_periods"]'::jsonb
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb) OR
    (action = 'AVAILABILITY_POLICY_UPDATED' AND details->'changed_fields' = '["availability_policy"]'::jsonb
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb) OR
    (action IN ('SCHEDULE_EXCEPTION_CREATED', 'SCHEDULE_EXCEPTION_UPDATED', 'SCHEDULE_EXCEPTION_REMOVED')
        AND details->'changed_fields' = '["schedule_exception"]'::jsonb
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb) OR
    (action IN ('BLOCKED_PERIOD_CREATED', 'BLOCKED_PERIOD_CANCELLED')
        AND details->'changed_fields' = '["blocked_period"]'::jsonb
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb) OR
    (action IN ('PUBLICATION_PUBLISHED', 'PUBLICATION_UNPUBLISHED')
        AND details->'changed_fields' = '["publication"]'::jsonb
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb) OR
    (action = 'PUBLIC_PROFILE_UPDATED'
        AND details->'changed_fields' = '["contact_phone"]'::jsonb
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb)
);

CREATE TABLE tenant_publication (
    tenant_id UUID PRIMARY KEY REFERENCES tenant (tenant_id) ON DELETE RESTRICT,
    contact_phone VARCHAR(32),
    published BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_tenant_publication_phone CHECK (
        contact_phone IS NULL OR (length(btrim(contact_phone)) BETWEEN 6 AND 32)
    ),
    CONSTRAINT ck_tenant_publication_version CHECK (version >= 0)
);

INSERT INTO tenant_publication (tenant_id)
SELECT tenant_id FROM tenant;

CREATE FUNCTION ensure_tenant_publication_state() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('INSERT INTO %I.tenant_publication (tenant_id) VALUES ($1)', TG_TABLE_SCHEMA)
        USING NEW.tenant_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tenant_publication_state_after_insert
AFTER INSERT ON tenant
FOR EACH ROW EXECUTE FUNCTION ensure_tenant_publication_state();

REVOKE ALL ON tenant_publication FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON tenant_publication TO agendamento_runtime;
