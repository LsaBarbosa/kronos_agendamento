CREATE TABLE subscription (
    subscription_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL UNIQUE REFERENCES tenant(tenant_id) ON DELETE RESTRICT,
    status VARCHAR(16) NOT NULL CHECK (status IN ('TRIALING', 'SUSPENDED')),
    trial_started_at TIMESTAMPTZ NOT NULL,
    trial_ends_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_subscription_id_tenant UNIQUE (subscription_id, tenant_id),
    CONSTRAINT ck_subscription_trial_duration CHECK (
        trial_ends_at = trial_started_at + INTERVAL '336 hours'
    ),
    CONSTRAINT ck_subscription_trial_status_version CHECK (
        (status = 'TRIALING' AND version = 1) OR
        (status = 'SUSPENDED' AND version = 2)
    )
);

CREATE TABLE commercial_trial_grant (
    grant_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL UNIQUE REFERENCES tenant(tenant_id) ON DELETE RESTRICT,
    commercial_identity_digest CHAR(64) NOT NULL UNIQUE
        CHECK (commercial_identity_digest ~ '^[0-9a-f]{64}$'),
    operation_id UUID NOT NULL UNIQUE,
    granted_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE subscription_history (
    event_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE RESTRICT,
    subscription_id UUID NOT NULL,
    event_type VARCHAR(16) NOT NULL CHECK (event_type IN ('TRIAL_GRANTED', 'TRIAL_EXPIRED')),
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL CHECK (to_status IN ('TRIALING', 'SUSPENDED')),
    event_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 1),
    operation_id UUID NOT NULL UNIQUE,
    CONSTRAINT fk_subscription_history_tenant_subscription
      FOREIGN KEY (subscription_id, tenant_id)
      REFERENCES subscription(subscription_id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT ck_subscription_history_transition CHECK (
        (event_type = 'TRIAL_GRANTED' AND from_status IS NULL AND to_status = 'TRIALING' AND version = 1) OR
        (event_type = 'TRIAL_EXPIRED' AND from_status = 'TRIALING' AND to_status = 'SUSPENDED' AND version = 2)
    )
);

CREATE INDEX ix_subscription_expiration
    ON subscription (trial_ends_at, tenant_id)
    WHERE status = 'TRIALING';

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_actor_ref;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_actor_ref CHECK (
    (actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN') OR
    (actor_type = 'SYSTEM' AND actor_ref IN ('CONTROLLED_TENANT_PROVISIONER', 'CONTROLLED_DEMO_PROVISIONER', 'PUBLIC_BOOKING', 'NO_SHOW_POLICY', 'SUBSCRIPTION_LIFECYCLE')) OR
    (actor_type = 'CLIENT' AND actor_ref = 'VERIFIED_CLIENT')
);

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action CHECK (action IN (
    'TENANT_CREATED', 'TENANT_RENAMED', 'TENANT_ADMIN_LINKED',
    'SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED',
    'SCHEDULE_UPDATED', 'AVAILABILITY_POLICY_UPDATED', 'CLIENT_NO_SHOW_POLICY_UPDATED',
    'CLIENT_SELF_BOOKING_BLOCKED', 'CLIENT_SELF_BOOKING_UNBLOCKED', 'CLIENT_SELF_BOOKING_RECONCILED',
    'SCHEDULE_EXCEPTION_CREATED', 'SCHEDULE_EXCEPTION_UPDATED', 'SCHEDULE_EXCEPTION_REMOVED',
    'BLOCKED_PERIOD_CREATED', 'BLOCKED_PERIOD_CANCELLED',
    'PUBLICATION_PUBLISHED', 'PUBLICATION_UNPUBLISHED', 'PUBLIC_PROFILE_UPDATED',
    'APPOINTMENT_CONFIRMED', 'APPOINTMENT_RESCHEDULED',
    'APPOINTMENT_CANCELLED_BY_PROVIDER', 'APPOINTMENT_CANCELLED_BY_CLIENT',
    'APPOINTMENT_COMPLETED', 'APPOINTMENT_NO_SHOW_RECORDED', 'APPOINTMENT_NO_SHOW_CORRECTED',
    'SUBSCRIPTION_TRIAL_GRANTED', 'SUBSCRIPTION_TRIAL_EXPIRED'
));

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action_actor;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action_actor CHECK (
    (action = 'TENANT_CREATED' AND actor_type = 'SYSTEM') OR
    (action = 'TENANT_ADMIN_LINKED' AND actor_type = 'SYSTEM' AND actor_ref = 'CONTROLLED_DEMO_PROVISIONER') OR
    (action = 'APPOINTMENT_CONFIRMED' AND actor_type = 'SYSTEM' AND actor_ref = 'PUBLIC_BOOKING') OR
    (action = 'CLIENT_SELF_BOOKING_BLOCKED' AND actor_type = 'SYSTEM' AND actor_ref = 'NO_SHOW_POLICY') OR
    (action IN ('SUBSCRIPTION_TRIAL_GRANTED', 'SUBSCRIPTION_TRIAL_EXPIRED') AND actor_type = 'SYSTEM' AND actor_ref = 'SUBSCRIPTION_LIFECYCLE') OR
    (action = 'TENANT_RENAMED' AND actor_type = 'ADMIN') OR
    (action = 'APPOINTMENT_CANCELLED_BY_CLIENT' AND actor_type = 'CLIENT' AND actor_ref = 'VERIFIED_CLIENT') OR
    (action IN (
        'SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED',
        'SCHEDULE_UPDATED', 'AVAILABILITY_POLICY_UPDATED', 'CLIENT_NO_SHOW_POLICY_UPDATED',
        'CLIENT_SELF_BOOKING_UNBLOCKED', 'CLIENT_SELF_BOOKING_RECONCILED',
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
    (action = 'CLIENT_SELF_BOOKING_RECONCILED'
      AND details ?& ARRAY['decision','count','threshold','policy_version','as_of']
      AND details->>'decision' IN ('ACTIVATED','ALREADY_RESTRICTED','BELOW_THRESHOLD','NO_UNUSED_CAUSAL_FACT')
      AND jsonb_typeof(details->'count') = 'number' AND (details->>'count')::integer BETWEEN 0 AND 100000
      AND jsonb_typeof(details->'threshold') = 'number' AND (details->>'threshold')::integer BETWEEN 1 AND 5
      AND jsonb_typeof(details->'policy_version') = 'number' AND (details->>'policy_version')::bigint >= 1
      AND jsonb_typeof(details->'as_of') = 'string'
      AND details - ARRAY['decision','count','threshold','policy_version','as_of']::text[] = '{}'::jsonb) OR
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
      AND NOT jsonb_path_exists(details->'changed_fields', '$[*] ? (@.type() != "string" || (@ != "name" && @ != "description" && @ != "duration_minutes" && @ != "buffer_after_minutes"))')) OR
    (action IN ('SUBSCRIPTION_TRIAL_GRANTED', 'SUBSCRIPTION_TRIAL_EXPIRED')
      AND details ?& ARRAY['changed_fields','version_before','version_after']
      AND details->'changed_fields' = '["subscription"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number'
      AND jsonb_typeof(details->'version_after') = 'number'
      AND (details->>'version_after')::bigint = (details->>'version_before')::bigint + 1
      AND ((action = 'SUBSCRIPTION_TRIAL_GRANTED' AND details->'version_before' = '0'::jsonb AND details->'version_after' = '1'::jsonb) OR
           (action = 'SUBSCRIPTION_TRIAL_EXPIRED' AND details->'version_before' = '1'::jsonb AND details->'version_after' = '2'::jsonb))
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb)
) IS TRUE);

GRANT SELECT, INSERT ON subscription TO agendamento_runtime;
GRANT UPDATE (status, version) ON subscription TO agendamento_runtime;
GRANT SELECT, INSERT ON commercial_trial_grant TO agendamento_runtime;
GRANT SELECT, INSERT ON subscription_history TO agendamento_runtime;
