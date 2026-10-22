-- Read-only preflight: V35 does not repair or infer historical billing state.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM subscription
        WHERE (
            (status = 'TRIALING' AND trial_started_at IS NOT NULL
                AND trial_ends_at = trial_started_at + INTERVAL '336 hours'
                AND plan_code IS NULL AND paid_through_at IS NULL AND version = 1)
            OR (status = 'SUSPENDED' AND trial_started_at IS NOT NULL
                AND trial_ends_at = trial_started_at + INTERVAL '336 hours'
                AND plan_code IS NULL AND paid_through_at IS NULL AND version = 2)
            OR (status = 'ACTIVE' AND plan_code IN ('MONTHLY', 'ANNUAL')
                AND paid_through_at IS NOT NULL AND version >= 1
                AND ((trial_started_at IS NULL AND trial_ends_at IS NULL)
                    OR (trial_started_at IS NOT NULL
                        AND trial_ends_at = trial_started_at + INTERVAL '336 hours')))
        ) IS NOT TRUE
    ) THEN
        RAISE EXCEPTION 'V35 cannot apply: incompatible legacy subscription state; inspect subscriptions read-only before migration';
    END IF;
END $$;

ALTER TABLE subscription ADD COLUMN grace_ends_at TIMESTAMPTZ;
ALTER TABLE subscription
    DROP CONSTRAINT ck_subscription_state,
    ADD CONSTRAINT ck_subscription_state CHECK ((
        (status = 'TRIALING'
            AND trial_started_at IS NOT NULL
            AND trial_ends_at = trial_started_at + INTERVAL '336 hours'
            AND plan_code IS NULL AND paid_through_at IS NULL AND grace_ends_at IS NULL
            AND version = 1)
        OR
        (status = 'SUSPENDED'
            AND trial_started_at IS NOT NULL
            AND trial_ends_at = trial_started_at + INTERVAL '336 hours'
            AND plan_code IS NULL AND paid_through_at IS NULL AND grace_ends_at IS NULL
            AND version = 2)
        OR
        (status = 'ACTIVE'
            AND plan_code IN ('MONTHLY', 'ANNUAL')
            AND paid_through_at IS NOT NULL AND grace_ends_at IS NULL
            AND ((trial_started_at IS NULL AND trial_ends_at IS NULL)
                OR (trial_started_at IS NOT NULL
                    AND trial_ends_at = trial_started_at + INTERVAL '336 hours'))
            AND version >= 1)
        OR
        (status = 'PAST_DUE'
            AND plan_code IN ('MONTHLY', 'ANNUAL')
            AND paid_through_at IS NOT NULL
            AND grace_ends_at = paid_through_at + INTERVAL '7 days'
            AND ((trial_started_at IS NULL AND trial_ends_at IS NULL)
                OR (trial_started_at IS NOT NULL
                    AND trial_ends_at = trial_started_at + INTERVAL '336 hours'))
            AND version >= 2)
        OR
        (status = 'SUSPENDED'
            AND plan_code IN ('MONTHLY', 'ANNUAL')
            AND paid_through_at IS NOT NULL
            AND grace_ends_at = paid_through_at + INTERVAL '7 days'
            AND ((trial_started_at IS NULL AND trial_ends_at IS NULL)
                OR (trial_started_at IS NOT NULL
                    AND trial_ends_at = trial_started_at + INTERVAL '336 hours'))
            AND version >= 3)
    ) IS TRUE);

ALTER TABLE subscription_history
    ALTER COLUMN event_type TYPE VARCHAR(40),
    DROP CONSTRAINT ck_subscription_history_event_type,
    DROP CONSTRAINT ck_subscription_history_to_status,
    DROP CONSTRAINT ck_subscription_history_transition,
    ADD CONSTRAINT ck_subscription_history_event_type CHECK (event_type IN (
        'TRIAL_GRANTED', 'TRIAL_EXPIRED', 'PAYMENT_CONFIRMED',
        'PAYMENT_PAST_DUE', 'PAYMENT_SUSPENDED'
    )),
    ADD CONSTRAINT ck_subscription_history_to_status
        CHECK (to_status IN ('TRIALING', 'PAST_DUE', 'SUSPENDED', 'ACTIVE')),
    ADD CONSTRAINT ck_subscription_history_transition CHECK ((
        (event_type = 'TRIAL_GRANTED' AND from_status IS NULL
            AND to_status = 'TRIALING' AND version = 1)
        OR (event_type = 'TRIAL_EXPIRED' AND from_status = 'TRIALING'
            AND to_status = 'SUSPENDED' AND version = 2)
        OR (event_type = 'PAYMENT_CONFIRMED' AND to_status = 'ACTIVE'
            AND ((from_status IS NULL AND version = 1)
                OR (from_status = 'TRIALING' AND version = 2)
                OR (from_status = 'PAST_DUE' AND version >= 3)
                OR (from_status = 'SUSPENDED' AND version >= 3)))
        OR (event_type = 'PAYMENT_PAST_DUE' AND from_status = 'ACTIVE'
            AND to_status = 'PAST_DUE' AND version >= 2)
        OR (event_type = 'PAYMENT_SUSPENDED' AND from_status = 'PAST_DUE'
            AND to_status = 'SUSPENDED' AND version >= 3)
    ) IS TRUE);

CREATE TABLE billing_dunning_cycle (
    cycle_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    grace_ends_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(20) NOT NULL CHECK (state IN ('OPEN', 'PAID', 'SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    CONSTRAINT uq_dunning_cycle_subscription_due UNIQUE (subscription_id, due_at),
    CONSTRAINT uq_dunning_cycle_tenant UNIQUE (cycle_id, tenant_id),
    CONSTRAINT fk_dunning_cycle_subscription FOREIGN KEY (subscription_id, tenant_id)
        REFERENCES subscription(subscription_id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT ck_dunning_cycle_grace CHECK (grace_ends_at = due_at + INTERVAL '7 days'),
    CONSTRAINT ck_dunning_cycle_closed CHECK (
        (state = 'OPEN' AND closed_at IS NULL) OR
        (state IN ('PAID', 'SUSPENDED') AND closed_at IS NOT NULL)
    )
);

CREATE TABLE billing_dunning_attempt (
    attempt_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    cycle_id UUID NOT NULL,
    logical_sequence SMALLINT NOT NULL CHECK (logical_sequence IN (0, 3, 6)),
    scheduled_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(28) NOT NULL CHECK (state IN (
        'SCHEDULED', 'BLOCKED_CONFIGURATION', 'SUBMITTING', 'OUTCOME_UNKNOWN',
        'ACCEPTED', 'DECLINED', 'CANCELLED'
    )),
    technical_attempts INTEGER NOT NULL DEFAULT 0 CHECK (technical_attempts >= 0),
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    provider_reference VARCHAR(192),
    last_error_code VARCHAR(48),
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_dunning_attempt_cycle_sequence UNIQUE (cycle_id, logical_sequence),
    CONSTRAINT uq_dunning_attempt_tenant UNIQUE (attempt_id, tenant_id),
    CONSTRAINT fk_dunning_attempt_cycle FOREIGN KEY (cycle_id, tenant_id)
        REFERENCES billing_dunning_cycle(cycle_id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT ck_dunning_attempt_claim CHECK (
        (state = 'SUBMITTING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state <> 'SUBMITTING' AND claim_token IS NULL AND lease_until IS NULL)
    )
);

CREATE INDEX ix_dunning_cycle_due ON billing_dunning_cycle (grace_ends_at, tenant_id)
    WHERE state = 'OPEN';
CREATE INDEX ix_dunning_attempt_due ON billing_dunning_attempt (scheduled_at, tenant_id)
    WHERE state IN ('SCHEDULED', 'BLOCKED_CONFIGURATION');

GRANT SELECT, INSERT ON billing_dunning_cycle TO agendamento_runtime;
GRANT UPDATE (state, closed_at) ON billing_dunning_cycle TO agendamento_runtime;
GRANT SELECT, INSERT ON billing_dunning_attempt TO agendamento_runtime;
GRANT UPDATE (state, updated_at) ON billing_dunning_attempt TO agendamento_runtime;
GRANT UPDATE (grace_ends_at) ON subscription TO agendamento_runtime;
REVOKE ALL ON billing_dunning_cycle, billing_dunning_attempt FROM PUBLIC;

-- Extend existing allowlists while retaining their previous predicates exactly.
DO $$
DECLARE old_check TEXT; old_expr TEXT;
BEGIN
    SELECT pg_get_constraintdef(oid) INTO old_check FROM pg_constraint
    WHERE conrelid = 'audit_event'::regclass AND conname = 'ck_audit_action';
    old_expr := substring(old_check FROM '(?s)^CHECK \((.*)\)$');
    IF old_expr IS NULL THEN RAISE EXCEPTION 'V35 cannot preserve ck_audit_action'; END IF;
    ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action;
    EXECUTE format('ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action CHECK ((%s) OR action IN (''SUBSCRIPTION_PAYMENT_PAST_DUE'', ''SUBSCRIPTION_PAYMENT_SUSPENDED''))', old_expr);

    SELECT pg_get_constraintdef(oid) INTO old_check FROM pg_constraint
    WHERE conrelid = 'audit_event'::regclass AND conname = 'ck_audit_action_actor';
    old_expr := substring(old_check FROM '(?s)^CHECK \((.*)\)$');
    IF old_expr IS NULL THEN RAISE EXCEPTION 'V35 cannot preserve ck_audit_action_actor'; END IF;
    ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action_actor;
    EXECUTE format('ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action_actor CHECK ((%s) OR (action IN (''SUBSCRIPTION_PAYMENT_PAST_DUE'', ''SUBSCRIPTION_PAYMENT_SUSPENDED'') AND actor_type = ''SYSTEM'' AND actor_ref = ''SUBSCRIPTION_LIFECYCLE''))', old_expr);

    SELECT pg_get_constraintdef(oid) INTO old_check FROM pg_constraint
    WHERE conrelid = 'audit_event'::regclass AND conname = 'ck_audit_details_allowlist';
    old_expr := substring(old_check FROM '(?s)^CHECK \((.*)\)$');
    IF old_expr IS NULL THEN RAISE EXCEPTION 'V35 cannot preserve ck_audit_details_allowlist'; END IF;
    ALTER TABLE audit_event DROP CONSTRAINT ck_audit_details_allowlist;
    EXECUTE format($sql$
      ALTER TABLE audit_event ADD CONSTRAINT ck_audit_details_allowlist CHECK ((%s) OR
        (action IN ('SUBSCRIPTION_PAYMENT_PAST_DUE','SUBSCRIPTION_PAYMENT_SUSPENDED')
          AND details ?& ARRAY['changed_fields','version_before','version_after']
          AND details->'changed_fields' = '["subscription"]'::jsonb
          AND jsonb_typeof(details->'version_before') = 'number'
          AND jsonb_typeof(details->'version_after') = 'number'
          AND (details->>'version_after')::numeric = (details->>'version_before')::numeric + 1
          AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) IS TRUE)
    $sql$, old_expr);

    SELECT pg_get_constraintdef(oid) INTO old_check FROM pg_constraint
    WHERE conrelid = 'outbox_event'::regclass AND conname = 'ck_outbox_payload_allowlist';
    old_expr := substring(old_check FROM '(?s)^CHECK \((.*)\)$');
    IF old_expr IS NULL THEN RAISE EXCEPTION 'V35 cannot preserve ck_outbox_payload_allowlist'; END IF;
    ALTER TABLE outbox_event DROP CONSTRAINT ck_outbox_payload_allowlist;
    EXECUTE format($sql$
      ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_payload_allowlist CHECK ((%s) OR
        (event_type IN ('SubscriptionPastDue','SubscriptionPaymentSuspended')
          AND aggregate_type = 'SUBSCRIPTION'
          AND payload ?& ARRAY['subscriptionId','version']
          AND payload - ARRAY['subscriptionId','version']::text[] = '{}'::jsonb
          AND jsonb_typeof(payload->'subscriptionId') = 'string'
          AND jsonb_typeof(payload->'version') = 'number') IS TRUE)
    $sql$, old_expr);
END $$;
