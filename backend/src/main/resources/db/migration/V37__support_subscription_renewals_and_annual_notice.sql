-- Read-only preflight: V36 did not persist provider account identity. A legacy reference
-- cannot safely be correlated to an authenticated event without inventing that identity.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM billing_dunning_attempt
        WHERE provider_reference IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'V37 cannot apply: legacy dunning provider references have no persisted account identity; inspect billing_dunning_attempt read-only and reconcile through the provider before migration';
    END IF;
END $$;

CREATE TABLE billing_subscription_contract (
    subscription_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    source_operation_id UUID NOT NULL,
    amount_minor_units BIGINT NOT NULL CHECK (amount_minor_units > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    terms_version VARCHAR(64) NOT NULL CHECK (terms_version <> ''),
    offer_version VARCHAR(64) NOT NULL CHECK (offer_version <> ''),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_subscription_contract_tenant UNIQUE (subscription_id, tenant_id),
    CONSTRAINT fk_subscription_contract_subscription FOREIGN KEY (subscription_id, tenant_id)
        REFERENCES subscription(subscription_id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_contract_operation FOREIGN KEY (source_operation_id, tenant_id)
        REFERENCES billing_checkout_operation(operation_id, tenant_id) ON DELETE RESTRICT
);

-- Reuse the latest authenticated, confirmed checkout terms when lineage exists.
INSERT INTO billing_subscription_contract
    (subscription_id, tenant_id, source_operation_id, amount_minor_units, currency,
     terms_version, offer_version, recorded_at)
SELECT DISTINCT ON (h.subscription_id)
       h.subscription_id, h.tenant_id, operation.operation_id, operation.amount_minor_units,
       operation.currency, operation.terms_version, operation.offer_version, h.event_at
FROM subscription_history h
JOIN billing_checkout_operation operation
  ON operation.operation_id = h.operation_id
 AND operation.tenant_id = h.tenant_id
 AND operation.state = 'CONFIRMED'
WHERE h.event_type = 'PAYMENT_CONFIRMED'
ORDER BY h.subscription_id, h.event_at DESC, h.event_id DESC
ON CONFLICT (subscription_id) DO NOTHING;

ALTER TABLE billing_dunning_attempt
    ADD COLUMN provider_account_id VARCHAR(128),
    ADD COLUMN last_provider_sequence BIGINT NOT NULL DEFAULT 0
        CHECK (last_provider_sequence >= 0);

ALTER TABLE billing_dunning_attempt
    ADD COLUMN contract_amount_minor_units BIGINT,
    ADD COLUMN contract_currency CHAR(3),
    ADD COLUMN contract_terms_version VARCHAR(64),
    ADD COLUMN contract_offer_version VARCHAR(64);

UPDATE billing_dunning_attempt attempt
SET contract_amount_minor_units = contract.amount_minor_units,
    contract_currency = contract.currency,
    contract_terms_version = contract.terms_version,
    contract_offer_version = contract.offer_version
FROM billing_dunning_cycle cycle
JOIN billing_subscription_contract contract
  ON contract.subscription_id = cycle.subscription_id
 AND contract.tenant_id = cycle.tenant_id
WHERE attempt.cycle_id = cycle.cycle_id
  AND attempt.tenant_id = cycle.tenant_id
  AND contract.recorded_at <= cycle.created_at;

-- Started legacy attempts without terms proven to predate the cycle cannot safely
-- validate a later provider event. Fail closed rather than use mutable current terms.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM billing_dunning_attempt attempt
        WHERE attempt.ever_started
          AND (attempt.contract_amount_minor_units IS NULL
              OR attempt.contract_currency IS NULL
              OR attempt.contract_terms_version IS NULL
              OR attempt.contract_offer_version IS NULL)
    ) THEN
        RAISE EXCEPTION 'V37 cannot apply: a started legacy dunning attempt has no immutable contract snapshot; reconcile its terms from authoritative records before upgrade';
    END IF;
END $$;

ALTER TABLE billing_dunning_attempt
    ADD CONSTRAINT ck_dunning_attempt_contract_snapshot CHECK ((
        (contract_amount_minor_units IS NULL AND contract_currency IS NULL
            AND contract_terms_version IS NULL AND contract_offer_version IS NULL)
        OR (contract_amount_minor_units > 0 AND contract_currency ~ '^[A-Z]{3}$'
            AND contract_terms_version <> '' AND contract_offer_version <> '')
    ) IS TRUE);

ALTER TABLE billing_dunning_attempt
    DROP CONSTRAINT ck_dunning_attempt_state,
    DROP CONSTRAINT ck_dunning_attempt_processing_state,
    ADD CONSTRAINT ck_dunning_attempt_state CHECK (state IN (
        'SCHEDULED', 'BLOCKED_CONFIGURATION', 'CLAIMED', 'SUBMITTING',
        'RETRYABLE_NO_EFFECT', 'OUTCOME_UNKNOWN', 'RECONCILING', 'ACCEPTED',
        'CONFIRMED', 'DECLINED', 'FAILED_FINAL', 'SKIPPED_LATE_RECOVERY', 'CANCELLED'
    )),
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
        OR (state IN ('ACCEPTED', 'CONFIRMED', 'DECLINED', 'FAILED_FINAL')
            AND ever_started AND skip_reason IS NULL)
        OR (state = 'SKIPPED_LATE_RECOVERY' AND NOT ever_started
            AND skip_reason IS NOT NULL)
        OR (state = 'CANCELLED' AND skip_reason IS NULL)
    ) IS TRUE),
    ADD CONSTRAINT ck_dunning_attempt_provider_identity CHECK ((
        (provider_reference IS NULL AND provider_account_id IS NULL)
        OR (provider_reference IS NOT NULL AND provider_code IS NOT NULL
            AND provider_account_id IS NOT NULL AND provider_account_id <> '')
    ) IS TRUE);

CREATE UNIQUE INDEX uq_dunning_provider_reference
    ON billing_dunning_attempt(provider_code, provider_account_id, provider_reference)
    WHERE provider_reference IS NOT NULL;

ALTER TABLE billing_event_inbox
    ADD COLUMN dunning_attempt_id UUID,
    DROP CONSTRAINT ck_billing_event_link,
    ADD CONSTRAINT fk_billing_event_dunning_attempt
        FOREIGN KEY (dunning_attempt_id, tenant_id)
        REFERENCES billing_dunning_attempt(attempt_id, tenant_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_billing_event_link CHECK ((
        (operation_id IS NULL AND dunning_attempt_id IS NULL
            AND tenant_id IS NULL AND disposition = 'UNLINKED')
        OR (operation_id IS NOT NULL AND dunning_attempt_id IS NULL
            AND tenant_id IS NOT NULL AND disposition <> 'UNLINKED')
        OR (operation_id IS NULL AND dunning_attempt_id IS NOT NULL
            AND tenant_id IS NOT NULL AND disposition <> 'UNLINKED')
    ) IS TRUE);

CREATE INDEX ix_billing_dunning_event_unlinked_reference
    ON billing_event_inbox(provider_code, provider_account_id, provider_reference, provider_sequence)
    WHERE operation_id IS NULL AND dunning_attempt_id IS NULL;

CREATE TABLE billing_annual_renewal_notice (
    notice_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    subscription_version BIGINT NOT NULL CHECK (subscription_version >= 1),
    paid_through_at TIMESTAMPTZ NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('SCHEDULED', 'READY', 'OBSOLETE')),
    obsolete_reason VARCHAR(48),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_annual_notice_period UNIQUE (subscription_id, paid_through_at),
    CONSTRAINT uq_annual_notice_tenant UNIQUE (notice_id, tenant_id),
    CONSTRAINT fk_annual_notice_subscription FOREIGN KEY (subscription_id, tenant_id)
        REFERENCES subscription(subscription_id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT ck_annual_notice_due CHECK ((scheduled_at = paid_through_at - INTERVAL '168 hours') IS TRUE),
    CONSTRAINT ck_annual_notice_state CHECK ((
        (state IN ('SCHEDULED', 'READY') AND obsolete_reason IS NULL)
        OR (state = 'OBSOLETE' AND obsolete_reason IN ('PERIOD_CHANGED', 'SUBSCRIPTION_INELIGIBLE'))
    ) IS TRUE)
);

CREATE INDEX ix_annual_renewal_notice_due
    ON billing_annual_renewal_notice(scheduled_at, tenant_id)
    WHERE state = 'SCHEDULED';

GRANT SELECT, INSERT, UPDATE ON billing_subscription_contract TO agendamento_runtime;
GRANT UPDATE (provider_account_id, last_provider_sequence, state, provider_code,
    provider_reference, updated_at, claim_token, lease_until, last_error_code)
    ON billing_dunning_attempt TO agendamento_runtime;
REVOKE UPDATE ON billing_event_inbox FROM agendamento_runtime;
GRANT UPDATE (dunning_attempt_id, tenant_id, operation_id, disposition)
    ON billing_event_inbox TO agendamento_runtime;
GRANT SELECT, INSERT ON billing_annual_renewal_notice TO agendamento_runtime;
GRANT UPDATE (state, obsolete_reason, updated_at) ON billing_annual_renewal_notice TO agendamento_runtime;
REVOKE ALL ON billing_subscription_contract, billing_annual_renewal_notice FROM PUBLIC;

COMMENT ON TABLE billing_subscription_contract IS
    'Immutable server-side terms snapshot for renewal attempts; absent legacy lineage blocks automatic renewal rather than inferring price.';
COMMENT ON TABLE billing_annual_renewal_notice IS
    'Deduplicated annual notice intent only; READY is not delivery and no provider is configured by this table.';
COMMENT ON COLUMN billing_annual_renewal_notice.scheduled_at IS
    'Technical schedule exactly 168 hours before the authenticated paid-through instant; no local calendar conversion.';
