CREATE TABLE client (
    client_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant (tenant_id) ON DELETE RESTRICT,
    display_name TEXT NOT NULL,
    normalized_phone VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uq_client_tenant_id UNIQUE (tenant_id, client_id),
    CONSTRAINT uq_client_tenant_phone UNIQUE (tenant_id, normalized_phone),
    CONSTRAINT ck_client_name CHECK (length(btrim(display_name)) BETWEEN 1 AND 120),
    CONSTRAINT ck_client_phone_e164 CHECK (normalized_phone ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT ck_client_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_client_version CHECK (version >= 1)
);

CREATE TABLE appointment (
    appointment_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant (tenant_id) ON DELETE RESTRICT,
    client_id UUID NOT NULL,
    service_id UUID NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    service_ends_at TIMESTAMPTZ NOT NULL,
    occupied_until TIMESTAMPTZ NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    client_name_snapshot TEXT NOT NULL,
    service_name_snapshot TEXT NOT NULL,
    service_duration_minutes INTEGER NOT NULL,
    service_buffer_after_minutes INTEGER NOT NULL,
    service_price_minor_snapshot BIGINT,
    service_currency_snapshot VARCHAR(3),
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uq_appointment_tenant_id UNIQUE (tenant_id, appointment_id),
    CONSTRAINT fk_appointment_tenant_client FOREIGN KEY (tenant_id, client_id)
        REFERENCES client (tenant_id, client_id) ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_tenant_service FOREIGN KEY (tenant_id, service_id)
        REFERENCES service (tenant_id, service_id) ON DELETE RESTRICT,
    CONSTRAINT ck_appointment_interval CHECK (
        starts_at < service_ends_at AND service_ends_at <= occupied_until
    ),
    CONSTRAINT ck_appointment_snapshot CHECK (
        length(btrim(client_name_snapshot)) BETWEEN 1 AND 120
        AND length(btrim(service_name_snapshot)) > 0
        AND service_duration_minutes > 0
        AND service_buffer_after_minutes >= 0
        AND service_ends_at = starts_at + make_interval(mins => service_duration_minutes)
        AND occupied_until = service_ends_at + make_interval(mins => service_buffer_after_minutes)
        AND ((service_price_minor_snapshot IS NULL AND service_currency_snapshot IS NULL)
             OR (service_price_minor_snapshot >= 0 AND service_currency_snapshot ~ '^[A-Z]{3}$'))
    ),
    CONSTRAINT ck_appointment_zone CHECK (length(time_zone) BETWEEN 1 AND 64),
    CONSTRAINT ck_appointment_status CHECK (status = 'CONFIRMED'),
    CONSTRAINT ck_appointment_version CHECK (version = 1)
);

CREATE INDEX ix_appointment_tenant_client ON appointment (tenant_id, client_id, created_at DESC);
CREATE INDEX ix_appointment_tenant_service ON appointment (tenant_id, service_id, starts_at);
CREATE INDEX ix_appointment_tenant_starts ON appointment (tenant_id, starts_at, occupied_until);
ALTER TABLE appointment ADD CONSTRAINT ex_appointment_tenant_occupied
    EXCLUDE USING gist (
        tenant_id WITH =,
        tstzrange(starts_at, occupied_until, '[)') WITH &&
    ) WHERE (status = 'CONFIRMED');

CREATE TABLE appointment_history (
    history_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    appointment_id UUID NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_ref VARCHAR(48) NOT NULL,
    reason_code VARCHAR(48),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_appointment_history_appointment FOREIGN KEY (tenant_id, appointment_id)
        REFERENCES appointment (tenant_id, appointment_id) ON DELETE RESTRICT,
    CONSTRAINT ck_appointment_history_initial CHECK (
        from_status IS NULL AND to_status = 'CONFIRMED'
        AND actor_type = 'PUBLIC' AND actor_ref = 'PUBLIC_BOOKING'
        AND reason_code = 'APPOINTMENT_CREATED'
    )
);
CREATE INDEX ix_appointment_history_tenant_appointment
    ON appointment_history (tenant_id, appointment_id, occurred_at);

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action CHECK (action IN (
    'TENANT_CREATED', 'TENANT_RENAMED', 'TENANT_ADMIN_LINKED',
    'SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED',
    'SCHEDULE_UPDATED', 'AVAILABILITY_POLICY_UPDATED',
    'SCHEDULE_EXCEPTION_CREATED', 'SCHEDULE_EXCEPTION_UPDATED', 'SCHEDULE_EXCEPTION_REMOVED',
    'BLOCKED_PERIOD_CREATED', 'BLOCKED_PERIOD_CANCELLED',
    'PUBLICATION_PUBLISHED', 'PUBLICATION_UNPUBLISHED', 'PUBLIC_PROFILE_UPDATED',
    'APPOINTMENT_CONFIRMED'
));
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_actor_ref;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_actor_ref CHECK (
    (actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN') OR
    (actor_type = 'SYSTEM' AND actor_ref IN (
        'CONTROLLED_TENANT_PROVISIONER', 'CONTROLLED_DEMO_PROVISIONER', 'PUBLIC_BOOKING'
    ))
);
ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action_actor;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action_actor CHECK (
    (action = 'TENANT_CREATED' AND actor_type = 'SYSTEM') OR
    (action = 'TENANT_ADMIN_LINKED' AND actor_type = 'SYSTEM' AND actor_ref = 'CONTROLLED_DEMO_PROVISIONER') OR
    (action = 'APPOINTMENT_CONFIRMED' AND actor_type = 'SYSTEM' AND actor_ref = 'PUBLIC_BOOKING') OR
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
    (action = 'APPOINTMENT_CONFIRMED' AND details = '{"changed_fields":["appointment"],"version_before":0,"version_after":1}'::jsonb) OR
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
    (action = 'PUBLIC_PROFILE_UPDATED' AND details->'changed_fields' = '["contact_phone"]'::jsonb
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb)
);

ALTER TABLE outbox_event DROP CONSTRAINT ck_outbox_payload_allowlist;
ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_payload_allowlist CHECK (
    (event_type = 'TENANT_RENAMED' AND aggregate_type = 'TENANT'
        AND payload ?& ARRAY['tenantId', 'version']
        AND payload - ARRAY['tenantId', 'version']::text[] = '{}'::jsonb
        AND jsonb_typeof(payload->'tenantId') = 'string' AND jsonb_typeof(payload->'version') = 'number') OR
    (event_type = 'ServiceCreated' AND aggregate_type = 'SERVICE'
        AND payload ?& ARRAY['serviceId', 'version']
        AND payload - ARRAY['serviceId', 'version']::text[] = '{}'::jsonb
        AND jsonb_typeof(payload->'serviceId') = 'string' AND jsonb_typeof(payload->'version') = 'number') OR
    (event_type = 'AppointmentConfirmed' AND aggregate_type = 'APPOINTMENT'
        AND payload ?& ARRAY['appointmentId', 'version']
        AND payload - ARRAY['appointmentId', 'version']::text[] = '{}'::jsonb
        AND jsonb_typeof(payload->'appointmentId') = 'string' AND jsonb_typeof(payload->'version') = 'number') OR
    (event_type IN ('ScheduleChanged', 'AvailabilityPolicyChanged')
        AND payload ?& ARRAY['tenantId', 'version']
        AND payload - ARRAY['tenantId', 'version']::text[] = '{}'::jsonb
        AND jsonb_typeof(payload->'tenantId') = 'string' AND jsonb_typeof(payload->'version') = 'number')
);

REVOKE ALL ON client, appointment, appointment_history FROM PUBLIC;
GRANT SELECT, INSERT ON client TO agendamento_runtime;
GRANT SELECT, INSERT ON appointment, appointment_history TO agendamento_runtime;
