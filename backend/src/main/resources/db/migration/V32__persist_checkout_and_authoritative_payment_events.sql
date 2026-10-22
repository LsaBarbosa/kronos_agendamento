ALTER TABLE subscription
    ALTER COLUMN trial_started_at DROP NOT NULL,
    ALTER COLUMN trial_ends_at DROP NOT NULL,
    ADD COLUMN plan_code VARCHAR(16),
    ADD COLUMN paid_through_at TIMESTAMPTZ;

ALTER TABLE subscription
    DROP CONSTRAINT ck_subscription_trial_duration,
    DROP CONSTRAINT ck_subscription_trial_status_version,
    DROP CONSTRAINT subscription_status_check,
    ADD CONSTRAINT ck_subscription_state CHECK (
        (status = 'TRIALING'
            AND trial_started_at IS NOT NULL
            AND trial_ends_at = trial_started_at + INTERVAL '336 hours'
            AND plan_code IS NULL AND paid_through_at IS NULL AND version = 1)
        OR
        (status = 'SUSPENDED'
            AND trial_started_at IS NOT NULL
            AND trial_ends_at = trial_started_at + INTERVAL '336 hours'
            AND plan_code IS NULL AND paid_through_at IS NULL AND version = 2)
        OR
        (status = 'ACTIVE'
            AND plan_code IN ('MONTHLY', 'ANNUAL')
            AND paid_through_at IS NOT NULL
            AND version >= 1)
    );

ALTER TABLE subscription_history
    ALTER COLUMN event_type TYPE VARCHAR(32),
    DROP CONSTRAINT subscription_history_event_type_check,
    DROP CONSTRAINT ck_subscription_history_transition,
    DROP CONSTRAINT subscription_history_to_status_check,
    ADD CONSTRAINT ck_subscription_history_event_type
        CHECK (event_type IN ('TRIAL_GRANTED', 'TRIAL_EXPIRED', 'PAYMENT_CONFIRMED')),
    ADD CONSTRAINT ck_subscription_history_to_status
        CHECK (to_status IN ('TRIALING', 'SUSPENDED', 'ACTIVE'));

ALTER TABLE subscription_history
    ADD CONSTRAINT ck_subscription_history_transition CHECK ((
        (event_type = 'TRIAL_GRANTED'
            AND from_status IS NULL AND to_status = 'TRIALING' AND version = 1)
        OR
        (event_type = 'TRIAL_EXPIRED'
            AND from_status = 'TRIALING' AND to_status = 'SUSPENDED' AND version = 2)
        OR
        (event_type = 'PAYMENT_CONFIRMED'
            AND to_status = 'ACTIVE'
            AND ((from_status IS NULL AND version = 1)
              OR (from_status = 'TRIALING' AND version = 2)
              OR (from_status = 'SUSPENDED' AND version = 3)))
    ) IS TRUE);

CREATE TABLE billing_checkout_operation (
    operation_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE RESTRICT,
    plan_code VARCHAR(16) NOT NULL CHECK (plan_code IN ('MONTHLY', 'ANNUAL')),
    amount_minor_units BIGINT NOT NULL CHECK (amount_minor_units > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    terms_version VARCHAR(64) NOT NULL CHECK (terms_version <> ''),
    offer_version VARCHAR(64) NOT NULL CHECK (offer_version <> ''),
    expected_subscription_version BIGINT NOT NULL CHECK (expected_subscription_version >= 0),
    provider_code VARCHAR(48) NOT NULL CHECK (provider_code ~ '^[a-z0-9][a-z0-9._-]{0,47}$'),
    provider_account_id VARCHAR(128) NOT NULL CHECK (provider_account_id <> ''),
    provider_reference VARCHAR(192),
    state VARCHAR(24) NOT NULL CHECK (state IN (
        'REQUESTED', 'SUBMITTING', 'CHECKOUT_READY', 'OUTCOME_UNKNOWN', 'CONFIRMED', 'DECLINED'
    )),
    last_provider_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_provider_sequence >= 0),
    provider_claim_token UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_billing_operation_tenant UNIQUE (operation_id, tenant_id),
    CONSTRAINT uq_billing_provider_reference UNIQUE (provider_code, provider_account_id, provider_reference),
    CONSTRAINT ck_billing_provider_claim CHECK (
        (state = 'SUBMITTING' AND provider_claim_token IS NOT NULL)
        OR (state <> 'SUBMITTING' AND provider_claim_token IS NULL)
    ),
    CONSTRAINT ck_billing_reference_state CHECK (
        (state IN ('CHECKOUT_READY', 'CONFIRMED', 'DECLINED') AND provider_reference IS NOT NULL)
        OR (state IN ('REQUESTED', 'SUBMITTING') AND provider_reference IS NULL)
        OR state = 'OUTCOME_UNKNOWN'
    )
);

CREATE UNIQUE INDEX uq_billing_open_checkout_per_tenant
    ON billing_checkout_operation (tenant_id)
    WHERE state IN ('REQUESTED', 'SUBMITTING', 'CHECKOUT_READY', 'OUTCOME_UNKNOWN');

CREATE TABLE billing_event_inbox (
    provider_code VARCHAR(48) NOT NULL,
    provider_account_id VARCHAR(128) NOT NULL,
    provider_event_id VARCHAR(192) NOT NULL,
    provider_reference VARCHAR(192) NOT NULL,
    provider_sequence BIGINT NOT NULL CHECK (provider_sequence >= 1),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('PENDING', 'CONFIRMED', 'DECLINED')),
    amount_minor_units BIGINT NOT NULL CHECK (amount_minor_units > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    occurred_at TIMESTAMPTZ NOT NULL,
    paid_through_at TIMESTAMPTZ,
    tenant_id UUID,
    operation_id UUID,
    disposition VARCHAR(16) NOT NULL DEFAULT 'UNLINKED'
        CHECK (disposition IN ('UNLINKED', 'PENDING', 'APPLIED', 'OLDER', 'DECLINED', 'MISMATCHED')),
    authenticated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (provider_code, provider_account_id, provider_event_id),
    CONSTRAINT fk_billing_event_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(tenant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_billing_event_operation FOREIGN KEY (operation_id, tenant_id)
        REFERENCES billing_checkout_operation(operation_id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT ck_billing_event_link CHECK (
        (operation_id IS NULL AND tenant_id IS NULL AND disposition = 'UNLINKED')
        OR (operation_id IS NOT NULL AND tenant_id IS NOT NULL AND disposition <> 'UNLINKED')
    ),
    CONSTRAINT ck_billing_event_paid_through CHECK (
        (outcome = 'CONFIRMED' AND paid_through_at IS NOT NULL)
        OR (outcome <> 'CONFIRMED' AND paid_through_at IS NULL)
    )
);

CREATE INDEX ix_billing_event_unlinked_reference
    ON billing_event_inbox (provider_code, provider_account_id, provider_reference, provider_sequence)
    WHERE operation_id IS NULL;

GRANT SELECT, INSERT, UPDATE ON billing_checkout_operation TO agendamento_runtime;
GRANT SELECT, INSERT, UPDATE ON billing_event_inbox TO agendamento_runtime;
REVOKE ALL ON billing_checkout_operation, billing_event_inbox FROM PUBLIC;

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
    'SUBSCRIPTION_TRIAL_GRANTED', 'SUBSCRIPTION_TRIAL_EXPIRED', 'SUBSCRIPTION_PAYMENT_CONFIRMED'
));

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action_actor;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action_actor CHECK (
    (action = 'TENANT_CREATED' AND actor_type = 'SYSTEM') OR
    (action = 'TENANT_ADMIN_LINKED' AND actor_type = 'SYSTEM' AND actor_ref = 'CONTROLLED_DEMO_PROVISIONER') OR
    (action = 'APPOINTMENT_CONFIRMED' AND actor_type = 'SYSTEM' AND actor_ref = 'PUBLIC_BOOKING') OR
    (action = 'CLIENT_SELF_BOOKING_BLOCKED' AND actor_type = 'SYSTEM' AND actor_ref = 'NO_SHOW_POLICY') OR
    (action IN ('SUBSCRIPTION_TRIAL_GRANTED', 'SUBSCRIPTION_TRIAL_EXPIRED', 'SUBSCRIPTION_PAYMENT_CONFIRMED') AND actor_type = 'SYSTEM' AND actor_ref = 'SUBSCRIPTION_LIFECYCLE') OR
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
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action = 'SUBSCRIPTION_PAYMENT_CONFIRMED'
      AND details ?& ARRAY['changed_fields','version_before','version_after']
      AND details->'changed_fields' = '["subscription"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number'
      AND jsonb_typeof(details->'version_after') = 'number'
      AND (details->>'version_after')::bigint = (details->>'version_before')::bigint + 1
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb)
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
      AND jsonb_typeof(payload->'tenantId') = 'string' AND jsonb_typeof(payload->'version') = 'number') OR
    (event_type = 'SubscriptionReactivated' AND aggregate_type = 'SUBSCRIPTION'
      AND payload ?& ARRAY['subscriptionId','version']
      AND payload - ARRAY['subscriptionId','version']::text[] = '{}'::jsonb
      AND jsonb_typeof(payload->'subscriptionId') = 'string'
      AND jsonb_typeof(payload->'version') = 'number')
);
