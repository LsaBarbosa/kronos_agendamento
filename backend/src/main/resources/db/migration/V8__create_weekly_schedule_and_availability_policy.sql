CREATE TABLE weekly_schedule (
    tenant_id UUID PRIMARY KEY REFERENCES tenant (tenant_id) ON DELETE RESTRICT,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_weekly_schedule_version CHECK (version > 0)
);

CREATE TABLE weekly_period (
    tenant_id UUID NOT NULL REFERENCES weekly_schedule (tenant_id) ON DELETE RESTRICT,
    weekday VARCHAR(9) NOT NULL,
    local_start TIME WITHOUT TIME ZONE NOT NULL,
    local_end TIME WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT ck_weekly_period_weekday CHECK (
        weekday IN ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')
    ),
    CONSTRAINT ck_weekly_period_same_day CHECK (local_start < local_end),
    CONSTRAINT ex_weekly_period_no_overlap EXCLUDE USING gist (
        tenant_id WITH =,
        weekday WITH =,
        tsrange(
            TIMESTAMP '2000-01-01' + local_start,
            TIMESTAMP '2000-01-01' + local_end,
            '[)'
        ) WITH &&
    )
);

CREATE INDEX ix_weekly_period_tenant_day_start
    ON weekly_period (tenant_id, weekday, local_start);

CREATE TABLE availability_policy (
    tenant_id UUID PRIMARY KEY REFERENCES tenant (tenant_id) ON DELETE RESTRICT,
    slot_step_minutes INTEGER NOT NULL,
    minimum_notice_hours INTEGER NOT NULL,
    booking_horizon_days INTEGER NOT NULL,
    cancellation_cutoff_hours INTEGER NOT NULL,
    reminder_offset_hours INTEGER NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_availability_policy_slot CHECK (slot_step_minutes IN (15, 30, 60)),
    CONSTRAINT ck_availability_policy_notice CHECK (minimum_notice_hours BETWEEN 1 AND 24),
    CONSTRAINT ck_availability_policy_horizon CHECK (booking_horizon_days IN (7, 30, 60, 90)),
    CONSTRAINT ck_availability_policy_cancellation CHECK (cancellation_cutoff_hours BETWEEN 2 AND 24),
    CONSTRAINT ck_availability_policy_reminder CHECK (reminder_offset_hours IN (2, 6, 12, 24)),
    CONSTRAINT ck_availability_policy_version CHECK (version > 0)
);

REVOKE ALL ON weekly_schedule, weekly_period, availability_policy FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON weekly_schedule, availability_policy TO agendamento_runtime;
GRANT SELECT, INSERT, DELETE ON weekly_period TO agendamento_runtime;

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action
    CHECK (action IN (
        'TENANT_CREATED', 'TENANT_RENAMED', 'TENANT_ADMIN_LINKED',
        'SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED',
        'SCHEDULE_UPDATED', 'AVAILABILITY_POLICY_UPDATED'
    ));

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action_actor;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action_actor
    CHECK (
        (action = 'TENANT_CREATED' AND actor_type = 'SYSTEM') OR
        (action = 'TENANT_RENAMED' AND actor_type = 'ADMIN') OR
        (action = 'TENANT_ADMIN_LINKED' AND actor_type = 'SYSTEM' AND actor_ref = 'CONTROLLED_DEMO_PROVISIONER') OR
        (action IN ('SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED',
                    'SCHEDULE_UPDATED', 'AVAILABILITY_POLICY_UPDATED')
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
            '{"changed_fields":["status"]}'::jsonb) OR
        (action = 'SCHEDULE_UPDATED' AND
            details->'changed_fields' = '["weekly_periods"]'::jsonb AND
            jsonb_typeof(details->'version_before') = 'number' AND
            jsonb_typeof(details->'version_after') = 'number' AND
            details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb) OR
        (action = 'AVAILABILITY_POLICY_UPDATED' AND
            details->'changed_fields' = '["availability_policy"]'::jsonb AND
            jsonb_typeof(details->'version_before') = 'number' AND
            jsonb_typeof(details->'version_after') = 'number' AND
            details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb)
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
            AND jsonb_typeof(payload->'version') = 'number') OR
        (event_type = 'ScheduleChanged' AND aggregate_type = 'SCHEDULE'
            AND payload ?& ARRAY['tenantId', 'version']
            AND payload - ARRAY['tenantId', 'version']::text[] = '{}'::jsonb
            AND jsonb_typeof(payload->'tenantId') = 'string'
            AND jsonb_typeof(payload->'version') = 'number') OR
        (event_type = 'AvailabilityPolicyChanged' AND aggregate_type = 'AVAILABILITY_POLICY'
            AND payload ?& ARRAY['tenantId', 'version']
            AND payload - ARRAY['tenantId', 'version']::text[] = '{}'::jsonb
            AND jsonb_typeof(payload->'tenantId') = 'string'
            AND jsonb_typeof(payload->'version') = 'number')
    );
