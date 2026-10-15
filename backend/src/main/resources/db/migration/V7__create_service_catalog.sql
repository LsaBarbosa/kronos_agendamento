CREATE TABLE service (
    service_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant (tenant_id) ON DELETE RESTRICT,
    name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    description TEXT,
    duration_minutes INTEGER NOT NULL,
    buffer_after_minutes INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_service_tenant_id UNIQUE (tenant_id, service_id),
    CONSTRAINT ck_service_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_service_normalized_name CHECK (length(normalized_name) > 0),
    CONSTRAINT ck_service_duration CHECK (duration_minutes > 0 AND duration_minutes % 15 = 0),
    CONSTRAINT ck_service_buffer CHECK (buffer_after_minutes >= 0 AND buffer_after_minutes % 15 = 0),
    CONSTRAINT ck_service_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_service_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_service_active_name
    ON service (tenant_id, normalized_name)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_service_tenant_status_created
    ON service (tenant_id, status, created_at, service_id);

REVOKE ALL ON service FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON service TO agendamento_runtime;

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action
    CHECK (action IN (
        'TENANT_CREATED', 'TENANT_RENAMED', 'TENANT_ADMIN_LINKED',
        'SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED'
    ));
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action_actor;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action_actor
    CHECK (
        (action = 'TENANT_CREATED' AND actor_type = 'SYSTEM') OR
        (action = 'TENANT_RENAMED' AND actor_type = 'ADMIN') OR
        (action = 'TENANT_ADMIN_LINKED' AND actor_type = 'SYSTEM' AND actor_ref = 'CONTROLLED_DEMO_PROVISIONER') OR
        (action IN ('SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED')
            AND actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN')
    );
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_details_allowlist;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_details_allowlist
    CHECK (
        (action = 'TENANT_CREATED' AND details = '{"changed_fields":["name","slug","timezone"]}'::jsonb) OR
        (action = 'TENANT_RENAMED' AND
            details->'changed_fields' = '["name"]'::jsonb AND
            jsonb_typeof(details->'version_before') = 'number' AND
            jsonb_typeof(details->'version_after') = 'number' AND
            details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb) OR
        (action = 'TENANT_ADMIN_LINKED' AND details = '{"changed_fields":["identity","membership"]}'::jsonb) OR
        (action = 'SERVICE_CREATED' AND details =
            '{"changed_fields":["name","description","duration_minutes","buffer_after_minutes","status"]}'::jsonb) OR
        (action = 'SERVICE_UPDATED' AND
            jsonb_typeof(details->'changed_fields') = 'array' AND
            jsonb_array_length(details->'changed_fields') > 0 AND
            jsonb_typeof(details->'version_before') = 'number' AND
            jsonb_typeof(details->'version_after') = 'number' AND
            details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb AND
            NOT jsonb_path_exists(
                details->'changed_fields',
                '$[*] ? (@.type() != "string" || (@ != "name" && @ != "description" && @ != "duration_minutes" && @ != "buffer_after_minutes"))'
            )) OR
        (action IN ('SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED') AND details =
            '{"changed_fields":["status"]}'::jsonb)
    );

ALTER TABLE outbox_event DROP CONSTRAINT ck_outbox_payload_allowlist;
ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_payload_allowlist
    CHECK (
        (event_type = 'TENANT_RENAMED' AND aggregate_type = 'TENANT'
            AND payload ?& ARRAY['tenantId', 'version']
            AND payload - ARRAY['tenantId', 'version']::text[] = '{}'::jsonb
            AND jsonb_typeof(payload->'tenantId') = 'string'
            AND jsonb_typeof(payload->'version') = 'number') OR
        (event_type = 'ServiceCreated' AND aggregate_type = 'SERVICE'
            AND payload ?& ARRAY['serviceId', 'version']
            AND payload - ARRAY['serviceId', 'version']::text[] = '{}'::jsonb
            AND jsonb_typeof(payload->'serviceId') = 'string'
            AND jsonb_typeof(payload->'version') = 'number')
    );
