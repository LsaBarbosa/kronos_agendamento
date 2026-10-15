DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agendamento_audit_owner') THEN
        CREATE ROLE agendamento_audit_owner NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agendamento_runtime') THEN
        CREATE ROLE agendamento_runtime NOLOGIN;
    END IF;
END
$$;

ALTER ROLE agendamento_audit_owner NOLOGIN;
ALTER ROLE agendamento_runtime NOLOGIN;

DO $$
DECLARE
    app_role_oid OID;
BEGIN
    SELECT oid INTO app_role_oid FROM pg_roles WHERE rolname = 'agendamento_app';
    IF app_role_oid IS NULL THEN
        RAISE EXCEPTION 'agendamento_app must be provisioned before Flyway V5';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_roles
        WHERE oid = app_role_oid
          AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR NOT rolinherit)
    ) THEN
        RAISE EXCEPTION 'agendamento_app has unsafe role attributes';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_auth_members
        WHERE member = app_role_oid AND roleid <> 'agendamento_runtime'::regrole
    ) THEN
        RAISE EXCEPTION 'agendamento_app has unexpected inherited role memberships';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_auth_members WHERE member = 'agendamento_runtime'::regrole
    ) THEN
        RAISE EXCEPTION 'agendamento_runtime must not inherit privileges from other roles';
    END IF;
END
$$;

CREATE TABLE audit_event (
    event_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    actor_id UUID,
    actor_type VARCHAR(16) NOT NULL,
    actor_ref VARCHAR(64) NOT NULL,
    action VARCHAR(96) NOT NULL,
    target_type VARCHAR(48) NOT NULL,
    target_id UUID NOT NULL,
    result VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    correlation_id UUID NOT NULL,
    trace_id UUID NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_audit_actor_type CHECK (actor_type IN ('ADMIN', 'SYSTEM')),
    CONSTRAINT ck_audit_actor_ref CHECK (
        (actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN') OR
        (actor_type = 'SYSTEM' AND actor_ref IN ('CONTROLLED_TENANT_PROVISIONER', 'CONTROLLED_DEMO_PROVISIONER'))
    ),
    CONSTRAINT ck_audit_actor_id CHECK (
        (actor_type = 'ADMIN' AND actor_id IS NOT NULL) OR
        (actor_type = 'SYSTEM' AND actor_id IS NULL)
    ),
    CONSTRAINT ck_audit_action CHECK (action IN ('TENANT_CREATED', 'TENANT_RENAMED', 'TENANT_ADMIN_LINKED')),
    CONSTRAINT ck_audit_action_actor CHECK (
        (action = 'TENANT_CREATED' AND actor_type = 'SYSTEM') OR
        (action = 'TENANT_RENAMED' AND actor_type = 'ADMIN') OR
        (action = 'TENANT_ADMIN_LINKED' AND actor_type = 'SYSTEM' AND actor_ref = 'CONTROLLED_DEMO_PROVISIONER')
    ),
    CONSTRAINT ck_audit_result CHECK (result IN ('COMMITTED')),
    CONSTRAINT ck_audit_details_allowlist CHECK (
        (action = 'TENANT_CREATED' AND details = '{"changed_fields":["name","slug","timezone"]}'::jsonb) OR
        (action = 'TENANT_RENAMED' AND
            details->'changed_fields' = '["name"]'::jsonb AND
            jsonb_typeof(details->'version_before') = 'number' AND
            jsonb_typeof(details->'version_after') = 'number' AND
            details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb) OR
        (action = 'TENANT_ADMIN_LINKED' AND details = '{"changed_fields":["identity","membership"]}'::jsonb)
    )
);

CREATE INDEX ix_audit_event_tenant_time ON audit_event (tenant_id, occurred_at DESC, event_id);
CREATE INDEX ix_audit_event_target ON audit_event (tenant_id, target_type, target_id, occurred_at DESC);

DO $$
BEGIN
    EXECUTE format('GRANT agendamento_audit_owner TO %I', current_user);
END
$$;

REVOKE ALL ON audit_event FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM agendamento_app;
GRANT USAGE ON SCHEMA agendamento TO agendamento_runtime;
GRANT USAGE ON SCHEMA agendamento TO agendamento_audit_owner;
GRANT SELECT, INSERT, UPDATE ON tenant TO agendamento_runtime;
GRANT SELECT ON admin_user, admin_tenant_membership TO agendamento_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON spring_session, spring_session_attributes TO agendamento_runtime;
GRANT SELECT, INSERT ON audit_event TO agendamento_runtime;
ALTER TABLE audit_event OWNER TO agendamento_audit_owner;

GRANT agendamento_runtime TO agendamento_app;
