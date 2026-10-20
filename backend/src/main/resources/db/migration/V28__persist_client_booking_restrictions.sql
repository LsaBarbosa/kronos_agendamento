CREATE TABLE client_booking_restriction (
    restriction_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    client_id UUID NOT NULL,
    source_appointment_id UUID NOT NULL,
    source_history_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    count_at_activation INTEGER NOT NULL,
    threshold_at_activation INTEGER NOT NULL,
    policy_version BIGINT NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    lifted_at TIMESTAMPTZ,
    lifted_by UUID,
    lift_reason VARCHAR(500),
    CONSTRAINT fk_client_restriction_client FOREIGN KEY (tenant_id, client_id)
        REFERENCES client (tenant_id, client_id) ON DELETE RESTRICT,
    CONSTRAINT fk_client_restriction_source FOREIGN KEY (tenant_id, source_appointment_id, source_history_id)
        REFERENCES appointment_history (tenant_id, appointment_id, history_id) ON DELETE RESTRICT,
    CONSTRAINT uq_client_restriction_tenant_id UNIQUE (tenant_id, restriction_id),
    CONSTRAINT uq_client_restriction_source UNIQUE (tenant_id, source_history_id),
    CONSTRAINT ck_client_restriction_status CHECK (status IN ('ACTIVE', 'LIFTED')),
    CONSTRAINT ck_client_restriction_decision CHECK (
        count_at_activation BETWEEN 1 AND 100000 AND
        threshold_at_activation BETWEEN 1 AND 5 AND
        count_at_activation >= threshold_at_activation AND
        policy_version >= 1 AND version >= 1 AND
        ((status = 'ACTIVE' AND lifted_at IS NULL AND lifted_by IS NULL AND lift_reason IS NULL) OR
         (status = 'LIFTED' AND lifted_at IS NOT NULL AND lifted_by IS NOT NULL AND
          lift_reason IS NOT NULL AND length(btrim(lift_reason)) BETWEEN 1 AND 500))
    )
);

CREATE UNIQUE INDEX uq_client_restriction_active
    ON client_booking_restriction (tenant_id, client_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_client_restriction_client_history
    ON client_booking_restriction (tenant_id, client_id, created_at DESC, restriction_id DESC);

CREATE TABLE client_booking_restriction_history (
    history_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    restriction_id UUID NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    count_at_decision INTEGER NOT NULL,
    threshold_at_decision INTEGER NOT NULL,
    policy_version BIGINT NOT NULL,
    restriction_version BIGINT NOT NULL,
    reason VARCHAR(500),
    CONSTRAINT fk_client_restriction_history_restriction
        FOREIGN KEY (tenant_id, restriction_id)
        REFERENCES client_booking_restriction (tenant_id, restriction_id) ON DELETE RESTRICT,
    CONSTRAINT ck_client_restriction_history_event CHECK (
        (event_type = 'ACTIVATED' AND actor_type = 'SYSTEM' AND actor_id IS NULL AND
         reason IS NULL AND restriction_version = 1 AND count_at_decision >= threshold_at_decision) OR
        (event_type = 'LIFTED' AND actor_type = 'ADMIN' AND actor_id IS NOT NULL AND
         reason IS NOT NULL AND length(btrim(reason)) BETWEEN 1 AND 500 AND restriction_version >= 2)
    ),
    CONSTRAINT ck_client_restriction_history_decision CHECK (
        count_at_decision BETWEEN 0 AND 100000 AND threshold_at_decision BETWEEN 1 AND 5 AND
        policy_version >= 1
    )
);
CREATE INDEX ix_client_restriction_history_tenant
    ON client_booking_restriction_history (tenant_id, restriction_id, occurred_at, history_id);

GRANT SELECT, INSERT, UPDATE ON client_booking_restriction TO agendamento_runtime;
GRANT SELECT, INSERT ON client_booking_restriction_history TO agendamento_runtime;

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_actor_ref;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_actor_ref CHECK (
    (actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN') OR
    (actor_type = 'SYSTEM' AND actor_ref IN ('CONTROLLED_TENANT_PROVISIONER', 'CONTROLLED_DEMO_PROVISIONER', 'PUBLIC_BOOKING', 'NO_SHOW_POLICY')) OR
    (actor_type = 'CLIENT' AND actor_ref = 'VERIFIED_CLIENT')
);

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action CHECK (action IN (
    'TENANT_CREATED', 'TENANT_RENAMED', 'TENANT_ADMIN_LINKED',
    'SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED',
    'SCHEDULE_UPDATED', 'AVAILABILITY_POLICY_UPDATED', 'CLIENT_NO_SHOW_POLICY_UPDATED',
    'CLIENT_SELF_BOOKING_BLOCKED', 'CLIENT_SELF_BOOKING_UNBLOCKED',
    'SCHEDULE_EXCEPTION_CREATED', 'SCHEDULE_EXCEPTION_UPDATED', 'SCHEDULE_EXCEPTION_REMOVED',
    'BLOCKED_PERIOD_CREATED', 'BLOCKED_PERIOD_CANCELLED',
    'PUBLICATION_PUBLISHED', 'PUBLICATION_UNPUBLISHED', 'PUBLIC_PROFILE_UPDATED',
    'APPOINTMENT_CONFIRMED', 'APPOINTMENT_RESCHEDULED',
    'APPOINTMENT_CANCELLED_BY_PROVIDER', 'APPOINTMENT_CANCELLED_BY_CLIENT',
    'APPOINTMENT_COMPLETED', 'APPOINTMENT_NO_SHOW_RECORDED', 'APPOINTMENT_NO_SHOW_CORRECTED'
));

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action_actor;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action_actor CHECK (
    (action = 'TENANT_CREATED' AND actor_type = 'SYSTEM') OR
    (action = 'TENANT_ADMIN_LINKED' AND actor_type = 'SYSTEM' AND actor_ref = 'CONTROLLED_DEMO_PROVISIONER') OR
    (action = 'APPOINTMENT_CONFIRMED' AND actor_type = 'SYSTEM' AND actor_ref = 'PUBLIC_BOOKING') OR
    (action = 'CLIENT_SELF_BOOKING_BLOCKED' AND actor_type = 'SYSTEM' AND actor_ref = 'NO_SHOW_POLICY') OR
    (action = 'TENANT_RENAMED' AND actor_type = 'ADMIN') OR
    (action = 'APPOINTMENT_CANCELLED_BY_CLIENT' AND actor_type = 'CLIENT' AND actor_ref = 'VERIFIED_CLIENT') OR
    (action IN (
        'SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED',
        'SCHEDULE_UPDATED', 'AVAILABILITY_POLICY_UPDATED', 'CLIENT_NO_SHOW_POLICY_UPDATED',
        'CLIENT_SELF_BOOKING_UNBLOCKED',
        'SCHEDULE_EXCEPTION_CREATED', 'SCHEDULE_EXCEPTION_UPDATED', 'SCHEDULE_EXCEPTION_REMOVED',
        'BLOCKED_PERIOD_CREATED', 'BLOCKED_PERIOD_CANCELLED',
        'PUBLICATION_PUBLISHED', 'PUBLICATION_UNPUBLISHED', 'PUBLIC_PROFILE_UPDATED',
        'APPOINTMENT_RESCHEDULED', 'APPOINTMENT_CONFIRMED',
        'APPOINTMENT_CANCELLED_BY_PROVIDER', 'APPOINTMENT_COMPLETED', 'APPOINTMENT_NO_SHOW_RECORDED',
        'APPOINTMENT_NO_SHOW_CORRECTED'
    ) AND actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN')
);

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_details_allowlist;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_details_allowlist CHECK ((
    (action = 'CLIENT_NO_SHOW_POLICY_UPDATED'
      AND details ?& ARRAY['changed_fields','version_before','version_after']
      AND details->'changed_fields' = '["no_show_threshold"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number'
      AND jsonb_typeof(details->'version_after') = 'number'
      AND (details->>'version_after')::numeric = (details->>'version_before')::numeric + 1
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action = 'CLIENT_SELF_BOOKING_BLOCKED'
      AND details ?& ARRAY['changed_fields','count','threshold','policy_version','version_after']
      AND details->'changed_fields' = '["self_booking_restriction"]'::jsonb
      AND jsonb_typeof(details->'count') = 'number' AND (details->>'count')::integer >= (details->>'threshold')::integer
      AND jsonb_typeof(details->'threshold') = 'number' AND (details->>'threshold')::integer BETWEEN 1 AND 5
      AND jsonb_typeof(details->'policy_version') = 'number' AND (details->>'policy_version')::bigint >= 1
      AND details->'version_after' = '1'::jsonb
      AND details - ARRAY['changed_fields','count','threshold','policy_version','version_after']::text[] = '{}'::jsonb) OR
    (action = 'CLIENT_SELF_BOOKING_UNBLOCKED'
      AND details ?& ARRAY['changed_fields','version_before','version_after','reason_provided']
      AND details->'changed_fields' = '["self_booking_restriction"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number'
      AND jsonb_typeof(details->'version_after') = 'number'
      AND (details->>'version_after')::bigint = (details->>'version_before')::bigint + 1
      AND details->'reason_provided' = 'true'::jsonb
      AND details - ARRAY['changed_fields','version_before','version_after','reason_provided']::text[] = '{}'::jsonb) OR
    (action = 'APPOINTMENT_CANCELLED_BY_CLIENT' AND actor_type = 'CLIENT'
      AND details ?& ARRAY['changed_fields','version_before','version_after']
      AND details->'changed_fields' = '["status"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number'
      AND jsonb_typeof(details->'version_after') = 'number'
      AND (details->>'version_after')::numeric = (details->>'version_before')::numeric + 1
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action IN ('APPOINTMENT_CANCELLED_BY_PROVIDER', 'APPOINTMENT_COMPLETED', 'APPOINTMENT_NO_SHOW_RECORDED', 'APPOINTMENT_NO_SHOW_CORRECTED')
      AND details ?& ARRAY['changed_fields','version_before','version_after','reason_provided']
      AND details->'changed_fields' = '["status"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number'
      AND jsonb_typeof(details->'version_after') = 'number'
      AND (details->>'version_after')::numeric = (details->>'version_before')::numeric + 1
      AND jsonb_typeof(details->'reason_provided') = 'boolean'
      AND (action <> 'APPOINTMENT_NO_SHOW_CORRECTED' OR details->'reason_provided' = 'true'::jsonb)
      AND details - ARRAY['changed_fields','version_before','version_after','reason_provided']::text[] = '{}'::jsonb) OR
    (action = 'APPOINTMENT_RESCHEDULED'
      AND details ?& ARRAY['changed_fields','version_before','version_after','override_policies','reason_provided']
      AND details->'changed_fields' = '["appointment"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND (details->>'version_after')::bigint = (details->>'version_before')::bigint + 1
      AND jsonb_typeof(details->'override_policies') = 'array'
      AND NOT jsonb_path_exists(details, '$.override_policies[*] ? (@ != "OUTSIDE_WEEKLY_SCHEDULE" && @ != "MIN_NOTICE")')
      AND jsonb_typeof(details->'reason_provided') = 'boolean'
      AND ((jsonb_array_length(details->'override_policies') = 0 AND details->'reason_provided' = 'false'::jsonb)
        OR (jsonb_array_length(details->'override_policies') > 0 AND details->'reason_provided' = 'true'::jsonb))
      AND details - ARRAY['changed_fields','version_before','version_after','override_policies','reason_provided']::text[] = '{}'::jsonb) OR
    (action = 'TENANT_CREATED' AND details = '{"changed_fields":["name","slug","timezone"]}'::jsonb) OR
    (action = 'TENANT_RENAMED' AND details->'changed_fields' = '["name"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action = 'TENANT_ADMIN_LINKED' AND details = '{"changed_fields":["identity","membership"]}'::jsonb) OR
    (action = 'APPOINTMENT_CONFIRMED' AND actor_type = 'SYSTEM'
      AND details = '{"changed_fields":["appointment"],"version_before":0,"version_after":1}'::jsonb) OR
    (action = 'APPOINTMENT_CONFIRMED' AND actor_type = 'ADMIN'
      AND details->'changed_fields' = '["appointment"]'::jsonb AND details->'version_before' = '0'::jsonb
      AND details->'version_after' = '1'::jsonb AND jsonb_typeof(details->'override_policies') = 'array'
      AND NOT jsonb_path_exists(details, '$.override_policies[*] ? (@ != "OUTSIDE_WEEKLY_SCHEDULE" && @ != "MIN_NOTICE")')
      AND jsonb_typeof(details->'reason_provided') = 'boolean'
      AND details->>'client_restriction_status' IN ('NOT_TRACKED', 'NOT_CONFIGURED', 'CLEAR', 'RESTRICTED')
      AND details - ARRAY['changed_fields','version_before','version_after','override_policies','reason_provided','client_restriction_status']::text[] = '{}'::jsonb
      AND ((jsonb_array_length(details->'override_policies') = 0 AND details->'reason_provided' = 'false'::jsonb)
        OR (jsonb_array_length(details->'override_policies') > 0 AND details->'reason_provided' = 'true'::jsonb))) OR
    (action = 'SERVICE_CREATED' AND details = '{"changed_fields":["name","description","duration_minutes","buffer_after_minutes","status"]}'::jsonb) OR
    ((action = 'SERVICE_ACTIVATED' OR action = 'SERVICE_DEACTIVATED') AND details = '{"changed_fields":["status"]}'::jsonb) OR
    (action = 'SCHEDULE_UPDATED' AND details->'changed_fields' = '["weekly_periods"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action = 'AVAILABILITY_POLICY_UPDATED' AND details->'changed_fields' = '["availability_policy"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action IN ('SCHEDULE_EXCEPTION_CREATED','SCHEDULE_EXCEPTION_UPDATED','SCHEDULE_EXCEPTION_REMOVED')
      AND details->'changed_fields' = '["schedule_exception"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action IN ('BLOCKED_PERIOD_CREATED','BLOCKED_PERIOD_CANCELLED')
      AND details->'changed_fields' = '["blocked_period"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action IN ('PUBLICATION_PUBLISHED','PUBLICATION_UNPUBLISHED')
      AND details->'changed_fields' = '["publication"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action = 'PUBLIC_PROFILE_UPDATED' AND details->'changed_fields' = '["contact_phone"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action = 'SERVICE_UPDATED' AND jsonb_typeof(details->'changed_fields') = 'array'
      AND jsonb_array_length(details->'changed_fields') > 0
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb
      AND NOT jsonb_path_exists(details->'changed_fields', '$[*] ? (@.type() != "string" || (@ != "name" && @ != "description" && @ != "duration_minutes" && @ != "buffer_after_minutes"))'))
) IS TRUE);

ALTER TABLE outbox_event DROP CONSTRAINT ck_outbox_payload_allowlist;
ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_payload_allowlist CHECK (
    (event_type IN ('AppointmentConfirmed', 'AppointmentRescheduled', 'AppointmentCancelled', 'AppointmentCompleted', 'ClientNoShowRecorded', 'ClientNoShowCorrected')
      AND aggregate_type = 'APPOINTMENT' AND payload ?& ARRAY['appointmentId','version']
      AND payload - ARRAY['appointmentId','version']::text[] = '{}'::jsonb
      AND jsonb_typeof(payload->'appointmentId') = 'string' AND jsonb_typeof(payload->'version') = 'number') OR
    (event_type = 'ClientSelfBookingBlocked' AND aggregate_type = 'CLIENT_RESTRICTION'
      AND payload ?& ARRAY['clientId','restrictionId','count','threshold','policyVersion','version']
      AND payload - ARRAY['clientId','restrictionId','count','threshold','policyVersion','version']::text[] = '{}'::jsonb
      AND jsonb_typeof(payload->'clientId') = 'string' AND jsonb_typeof(payload->'restrictionId') = 'string'
      AND jsonb_typeof(payload->'count') = 'number' AND jsonb_typeof(payload->'threshold') = 'number'
      AND jsonb_typeof(payload->'policyVersion') = 'number' AND payload->'version' = '1'::jsonb) OR
    (event_type = 'ClientSelfBookingUnblocked' AND aggregate_type = 'CLIENT_RESTRICTION'
      AND payload ?& ARRAY['clientId','restrictionId','version']
      AND payload - ARRAY['clientId','restrictionId','version']::text[] = '{}'::jsonb
      AND jsonb_typeof(payload->'clientId') = 'string' AND jsonb_typeof(payload->'restrictionId') = 'string'
      AND jsonb_typeof(payload->'version') = 'number') OR
    (event_type = 'TENANT_RENAMED' AND aggregate_type = 'TENANT'
      AND payload ?& ARRAY['tenantId','version'] AND payload - ARRAY['tenantId','version']::text[] = '{}'::jsonb
      AND jsonb_typeof(payload->'tenantId') = 'string' AND jsonb_typeof(payload->'version') = 'number') OR
    (event_type = 'ServiceCreated' AND aggregate_type = 'SERVICE'
      AND payload ?& ARRAY['serviceId','version'] AND payload - ARRAY['serviceId','version']::text[] = '{}'::jsonb
      AND jsonb_typeof(payload->'serviceId') = 'string' AND jsonb_typeof(payload->'version') = 'number') OR
    (event_type IN ('ScheduleChanged','AvailabilityPolicyChanged')
      AND payload ?& ARRAY['tenantId','version'] AND payload - ARRAY['tenantId','version']::text[] = '{}'::jsonb
      AND jsonb_typeof(payload->'tenantId') = 'string' AND jsonb_typeof(payload->'version') = 'number')
);
