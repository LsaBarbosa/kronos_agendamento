CREATE TABLE schedule_exception (
    exception_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant (tenant_id) ON DELETE RESTRICT,
    local_date DATE NOT NULL,
    kind VARCHAR(8) NOT NULL,
    reason VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_schedule_exception_tenant_date UNIQUE (tenant_id, local_date),
    CONSTRAINT uq_schedule_exception_tenant_id UNIQUE (tenant_id, exception_id),
    CONSTRAINT ck_schedule_exception_kind CHECK (kind IN ('CLOSED', 'SPECIAL')),
    CONSTRAINT ck_schedule_exception_reason CHECK (reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_schedule_exception_version CHECK (version > 0)
);

CREATE TABLE schedule_exception_period (
    tenant_id UUID NOT NULL,
    exception_id UUID NOT NULL,
    local_start TIME WITHOUT TIME ZONE NOT NULL,
    local_end TIME WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT fk_schedule_exception_period_parent
        FOREIGN KEY (tenant_id, exception_id) REFERENCES schedule_exception (tenant_id, exception_id) ON DELETE CASCADE,
    CONSTRAINT ck_schedule_exception_period_order CHECK (local_start < local_end),
    CONSTRAINT ck_schedule_exception_period_quarter_hour CHECK (
        mod(extract(minute FROM local_start)::INTEGER, 15) = 0 AND extract(second FROM local_start) = 0
        AND mod(extract(minute FROM local_end)::INTEGER, 15) = 0 AND extract(second FROM local_end) = 0
        AND local_end < TIME '24:00'
    ),
    CONSTRAINT ex_schedule_exception_period_no_overlap EXCLUDE USING gist (
        exception_id WITH =,
        tsrange(TIMESTAMP '2000-01-01' + local_start, TIMESTAMP '2000-01-01' + local_end, '[)') WITH &&
    )
);

CREATE INDEX ix_schedule_exception_tenant_date ON schedule_exception (tenant_id, local_date);

CREATE FUNCTION validate_schedule_exception_period_set() RETURNS trigger
LANGUAGE plpgsql
SET search_path = agendamento, pg_temp
AS $$
DECLARE
    target_tenant UUID;
    target_exception UUID;
    target_kind VARCHAR(8);
    period_count BIGINT;
BEGIN
    target_tenant := COALESCE(NEW.tenant_id, OLD.tenant_id);
    target_exception := COALESCE(NEW.exception_id, OLD.exception_id);
    SELECT kind INTO target_kind FROM schedule_exception
     WHERE tenant_id = target_tenant AND exception_id = target_exception;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT count(*) INTO period_count FROM schedule_exception_period
     WHERE tenant_id = target_tenant AND exception_id = target_exception;
    IF (target_kind = 'CLOSED' AND period_count <> 0)
       OR (target_kind = 'SPECIAL' AND period_count = 0) THEN
        RAISE EXCEPTION 'schedule exception kind and period list are inconsistent'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_schedule_exception_period_set';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_schedule_exception_period_parent
AFTER INSERT OR UPDATE OR DELETE ON schedule_exception
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_schedule_exception_period_set();

CREATE CONSTRAINT TRIGGER ct_schedule_exception_period_children
AFTER INSERT OR UPDATE OR DELETE ON schedule_exception_period
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_schedule_exception_period_set();

CREATE TABLE blocked_period (
    block_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant (tenant_id) ON DELETE RESTRICT,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    time_zone VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 1,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_blocked_period_order CHECK (starts_at < ends_at),
    CONSTRAINT ck_blocked_period_reason CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_blocked_period_status CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT ck_blocked_period_version CHECK (version > 0),
    CONSTRAINT ck_blocked_period_cancel_state CHECK (
        (status = 'ACTIVE' AND cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL
            AND length(btrim(cancellation_reason)) BETWEEN 1 AND 500)
    ),
    CONSTRAINT ck_blocked_period_quarter_hour CHECK (
        mod(extract(minute FROM starts_at AT TIME ZONE time_zone)::INTEGER, 15) = 0
        AND extract(second FROM starts_at AT TIME ZONE time_zone) = 0
        AND mod(extract(minute FROM ends_at AT TIME ZONE time_zone)::INTEGER, 15) = 0
        AND extract(second FROM ends_at AT TIME ZONE time_zone) = 0
    )
);

CREATE INDEX ix_blocked_period_tenant_window ON blocked_period (tenant_id, starts_at, ends_at);

CREATE TABLE schedule_revision (
    tenant_id UUID PRIMARY KEY REFERENCES tenant (tenant_id) ON DELETE RESTRICT,
    revision BIGINT NOT NULL CHECK (revision > 0)
);

INSERT INTO schedule_revision(tenant_id, revision)
SELECT tenant_id, max(aggregate_version)
FROM outbox_event
WHERE aggregate_type = 'SCHEDULE'
GROUP BY tenant_id
HAVING max(aggregate_version) > 0;

REVOKE ALL ON schedule_exception, schedule_exception_period, blocked_period FROM PUBLIC;
GRANT SELECT, DELETE ON schedule_exception TO agendamento_runtime;
GRANT INSERT (exception_id, tenant_id, local_date, kind, reason) ON schedule_exception TO agendamento_runtime;
GRANT UPDATE (local_date, kind, reason, version, updated_at) ON schedule_exception TO agendamento_runtime;
GRANT SELECT, INSERT, DELETE ON schedule_exception_period TO agendamento_runtime;
GRANT SELECT ON blocked_period TO agendamento_runtime;
GRANT INSERT (block_id, tenant_id, starts_at, ends_at, time_zone, reason) ON blocked_period TO agendamento_runtime;
GRANT UPDATE (status, version, cancelled_at, cancellation_reason, updated_at) ON blocked_period TO agendamento_runtime;
GRANT SELECT (tenant_id, revision), INSERT (tenant_id, revision), UPDATE (revision)
    ON schedule_revision TO agendamento_runtime;

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action CHECK (action IN (
    'TENANT_CREATED', 'TENANT_RENAMED', 'TENANT_ADMIN_LINKED',
    'SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED',
    'SCHEDULE_UPDATED', 'AVAILABILITY_POLICY_UPDATED',
    'SCHEDULE_EXCEPTION_CREATED', 'SCHEDULE_EXCEPTION_UPDATED', 'SCHEDULE_EXCEPTION_REMOVED',
    'BLOCKED_PERIOD_CREATED', 'BLOCKED_PERIOD_CANCELLED'
));

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action_actor;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action_actor CHECK (
    (action = 'TENANT_CREATED' AND actor_type = 'SYSTEM') OR
    (action = 'TENANT_RENAMED' AND actor_type = 'ADMIN') OR
    (action = 'TENANT_ADMIN_LINKED' AND actor_type = 'SYSTEM' AND actor_ref = 'CONTROLLED_DEMO_PROVISIONER') OR
    (action IN ('SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED',
        'SCHEDULE_UPDATED', 'AVAILABILITY_POLICY_UPDATED', 'SCHEDULE_EXCEPTION_CREATED',
        'SCHEDULE_EXCEPTION_UPDATED', 'SCHEDULE_EXCEPTION_REMOVED', 'BLOCKED_PERIOD_CREATED',
        'BLOCKED_PERIOD_CANCELLED') AND actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN')
);

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_details_allowlist;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_details_allowlist CHECK (
    (action = 'TENANT_CREATED' AND details = '{"changed_fields":["name","slug","timezone"]}'::jsonb) OR
    (action = 'TENANT_RENAMED' AND details->'changed_fields' = '["name"]'::jsonb AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number' AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action = 'TENANT_ADMIN_LINKED' AND details = '{"changed_fields":["identity","membership"]}'::jsonb) OR
    (action = 'SERVICE_CREATED' AND details = '{"changed_fields":["name","description","duration_minutes","buffer_after_minutes","status"]}'::jsonb) OR
    (action = 'SERVICE_UPDATED' AND jsonb_typeof(details->'changed_fields') = 'array' AND jsonb_array_length(details->'changed_fields') > 0 AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number' AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb AND NOT jsonb_path_exists(details->'changed_fields', '$[*] ? (@.type() != "string" || (@ != "name" && @ != "description" && @ != "duration_minutes" && @ != "buffer_after_minutes"))')) OR
    (action IN ('SERVICE_ACTIVATED','SERVICE_DEACTIVATED') AND details = '{"changed_fields":["status"]}'::jsonb) OR
    (action = 'SCHEDULE_UPDATED' AND details->'changed_fields' = '["weekly_periods"]'::jsonb AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number' AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action = 'AVAILABILITY_POLICY_UPDATED' AND details->'changed_fields' = '["availability_policy"]'::jsonb AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number' AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action IN ('SCHEDULE_EXCEPTION_CREATED','SCHEDULE_EXCEPTION_UPDATED','SCHEDULE_EXCEPTION_REMOVED','BLOCKED_PERIOD_CREATED','BLOCKED_PERIOD_CANCELLED') AND
        details->'changed_fields' = jsonb_build_array(CASE action
            WHEN 'SCHEDULE_EXCEPTION_CREATED' THEN 'schedule_exception'
            WHEN 'SCHEDULE_EXCEPTION_UPDATED' THEN 'schedule_exception'
            WHEN 'SCHEDULE_EXCEPTION_REMOVED' THEN 'schedule_exception'
            WHEN 'BLOCKED_PERIOD_CREATED' THEN 'blocked_period'
            ELSE 'blocked_period' END) AND
        jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number' AND
        details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb)
);
