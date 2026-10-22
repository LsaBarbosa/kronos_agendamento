-- Read-only preflight: exact elapsed-hour grace cannot be inferred from a legacy local-day value.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM subscription
        WHERE status IN ('PAST_DUE', 'SUSPENDED')
          AND grace_ends_at IS DISTINCT FROM paid_through_at + INTERVAL '168 hours'
    ) OR EXISTS (
        SELECT 1 FROM billing_dunning_cycle
        WHERE grace_ends_at IS DISTINCT FROM due_at + INTERVAL '168 hours'
    ) THEN
        RAISE EXCEPTION 'V36 cannot apply: dunning grace differs from the approved 168-hour interval; inspect legacy subscription/cycle rows read-only';
    END IF;
END $$;

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
            AND grace_ends_at = paid_through_at + INTERVAL '168 hours'
            AND ((trial_started_at IS NULL AND trial_ends_at IS NULL)
                OR (trial_started_at IS NOT NULL
                    AND trial_ends_at = trial_started_at + INTERVAL '336 hours'))
            AND version >= 2)
        OR
        (status = 'SUSPENDED'
            AND plan_code IN ('MONTHLY', 'ANNUAL')
            AND paid_through_at IS NOT NULL
            AND grace_ends_at = paid_through_at + INTERVAL '168 hours'
            AND ((trial_started_at IS NULL AND trial_ends_at IS NULL)
                OR (trial_started_at IS NOT NULL
                    AND trial_ends_at = trial_started_at + INTERVAL '336 hours'))
            AND version >= 3)
    ) IS TRUE);

ALTER TABLE billing_dunning_cycle
    DROP CONSTRAINT ck_dunning_cycle_grace,
    ADD CONSTRAINT ck_dunning_cycle_grace
        CHECK ((grace_ends_at = due_at + INTERVAL '168 hours') IS TRUE);

ALTER TABLE billing_dunning_attempt
    ADD COLUMN ever_started BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN skip_reason VARCHAR(48),
    ADD COLUMN provider_code VARCHAR(48),
    ADD COLUMN send_claim_token UUID,
    ADD COLUMN external_idempotency_key UUID;

UPDATE billing_dunning_attempt
SET external_idempotency_key = attempt_id;

ALTER TABLE billing_dunning_attempt
    ALTER COLUMN external_idempotency_key SET NOT NULL;

-- The old state itself is evidence that a send had begun. The timestamp of that old
-- beginning is unavailable, so V36 preserves only this boolean fact.
UPDATE billing_dunning_attempt
SET ever_started = TRUE
WHERE state IN ('SUBMITTING', 'OUTCOME_UNKNOWN', 'ACCEPTED', 'DECLINED');

UPDATE billing_dunning_attempt
SET send_claim_token = claim_token
WHERE state = 'SUBMITTING';

ALTER TABLE billing_dunning_attempt
    DROP CONSTRAINT billing_dunning_attempt_state_check,
    DROP CONSTRAINT ck_dunning_attempt_claim,
    ADD CONSTRAINT ck_dunning_attempt_state CHECK (state IN (
        'SCHEDULED', 'BLOCKED_CONFIGURATION', 'CLAIMED', 'SUBMITTING',
        'RETRYABLE_NO_EFFECT', 'OUTCOME_UNKNOWN', 'RECONCILING', 'ACCEPTED',
        'DECLINED', 'FAILED_FINAL', 'SKIPPED_LATE_RECOVERY', 'CANCELLED'
    )),
    ADD CONSTRAINT ck_dunning_attempt_claim CHECK ((
        (state IN ('CLAIMED', 'SUBMITTING', 'RECONCILING')
            AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state NOT IN ('CLAIMED', 'SUBMITTING', 'RECONCILING')
            AND claim_token IS NULL AND lease_until IS NULL)
    ) IS TRUE),
    ADD CONSTRAINT ck_dunning_attempt_processing_state CHECK ((
        (state = 'SCHEDULED' AND NOT ever_started AND skip_reason IS NULL)
        OR (state = 'BLOCKED_CONFIGURATION' AND NOT ever_started AND skip_reason IS NULL)
        OR (state = 'CLAIMED' AND skip_reason IS NULL)
        OR (state = 'SUBMITTING' AND ever_started AND send_claim_token IS NOT NULL
            AND skip_reason IS NULL)
        OR (state = 'RETRYABLE_NO_EFFECT' AND ever_started
            AND next_attempt_at IS NOT NULL AND skip_reason IS NULL)
        OR (state = 'OUTCOME_UNKNOWN' AND ever_started AND skip_reason IS NULL)
        OR (state = 'RECONCILING' AND ever_started AND skip_reason IS NULL)
        OR (state = 'ACCEPTED' AND ever_started AND skip_reason IS NULL)
        OR (state = 'DECLINED' AND ever_started AND skip_reason IS NULL)
        OR (state = 'FAILED_FINAL' AND ever_started AND skip_reason IS NULL)
        OR (state = 'SKIPPED_LATE_RECOVERY' AND NOT ever_started
            AND skip_reason IS NOT NULL)
        OR (state = 'CANCELLED' AND skip_reason IS NULL)
    ) IS TRUE),
    ADD CONSTRAINT uq_dunning_attempt_external_identity
        UNIQUE (external_idempotency_key);

CREATE INDEX ix_dunning_attempt_processing
    ON billing_dunning_attempt
       (scheduled_at, next_attempt_at, tenant_id)
    WHERE state IN ('SCHEDULED', 'BLOCKED_CONFIGURATION', 'RETRYABLE_NO_EFFECT', 'OUTCOME_UNKNOWN');

GRANT UPDATE (
    state, technical_attempts, claim_token, lease_until, provider_reference,
    last_error_code, updated_at, ever_started, next_attempt_at, skip_reason,
    provider_code, send_claim_token
) ON billing_dunning_attempt TO agendamento_runtime;
REVOKE DELETE, TRUNCATE ON billing_dunning_attempt FROM agendamento_runtime;
REVOKE ALL ON billing_dunning_attempt FROM PUBLIC;

COMMENT ON COLUMN billing_dunning_attempt.ever_started IS
    'True once a send authorization is committed; never reset by retry, payment, suspension or restart.';
COMMENT ON COLUMN billing_dunning_attempt.send_claim_token IS
    'Fence for the currently authorized external send; permits its late result to be recorded after lease expiry unless a newer send is authorized.';
COMMENT ON COLUMN billing_dunning_attempt.external_idempotency_key IS
    'Stable logical operation identity supplied unchanged to any provider retry; equal to the persisted attempt identity.';
