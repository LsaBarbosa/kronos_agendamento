-- The source checkout is the only persisted evidence from which the subscription
-- contract plan can be recovered. Fail closed if the lineage or terms disagree.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM billing_subscription_contract contract
        LEFT JOIN billing_checkout_operation operation
          ON operation.operation_id = contract.source_operation_id
         AND operation.tenant_id = contract.tenant_id
        WHERE operation.operation_id IS NULL
           OR operation.state <> 'CONFIRMED'
           OR operation.amount_minor_units <> contract.amount_minor_units
           OR operation.currency <> contract.currency
           OR operation.terms_version <> contract.terms_version
           OR operation.offer_version <> contract.offer_version
           OR operation.plan_code NOT IN ('MONTHLY', 'ANNUAL')
           OR NOT EXISTS (
              SELECT 1 FROM subscription_history history
              WHERE history.tenant_id = contract.tenant_id
                AND history.subscription_id = contract.subscription_id
                AND history.operation_id = contract.source_operation_id
                AND history.event_type = 'PAYMENT_CONFIRMED'
                AND history.event_at <= contract.recorded_at
           )
    ) THEN
        RAISE EXCEPTION 'V40 cannot apply: subscription contract lacks matching confirmed checkout evidence; inspect billing_subscription_contract read-only';
    END IF;
END $$;

ALTER TABLE billing_subscription_contract ADD COLUMN plan_code VARCHAR(16);
UPDATE billing_subscription_contract contract
SET plan_code = operation.plan_code
FROM billing_checkout_operation operation
WHERE operation.operation_id = contract.source_operation_id
  AND operation.tenant_id = contract.tenant_id;
ALTER TABLE billing_subscription_contract
    ALTER COLUMN plan_code SET NOT NULL,
    ADD CONSTRAINT ck_subscription_contract_plan
        CHECK (plan_code IN ('MONTHLY', 'ANNUAL'));

-- An attempt is backfilled only when its already-persisted immutable terms match
-- the contract lineage captured before that dunning cycle was created.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM billing_dunning_attempt attempt
        JOIN billing_dunning_cycle cycle USING (cycle_id, tenant_id)
        WHERE (attempt.ever_started OR attempt.contract_amount_minor_units IS NOT NULL)
          AND NOT EXISTS (
              SELECT 1
              FROM billing_subscription_contract contract
              JOIN billing_checkout_operation operation
                ON operation.operation_id = contract.source_operation_id
               AND operation.tenant_id = contract.tenant_id
               AND operation.state = 'CONFIRMED'
              WHERE contract.subscription_id = cycle.subscription_id
                AND contract.tenant_id = cycle.tenant_id
                AND contract.recorded_at <= cycle.created_at
                AND contract.amount_minor_units = attempt.contract_amount_minor_units
                AND contract.currency = attempt.contract_currency
                AND contract.terms_version = attempt.contract_terms_version
                AND contract.offer_version = attempt.contract_offer_version
                AND contract.plan_code = operation.plan_code
                AND EXISTS (
                    SELECT 1 FROM subscription_history history
                    WHERE history.tenant_id = contract.tenant_id
                      AND history.subscription_id = contract.subscription_id
                      AND history.operation_id = contract.source_operation_id
                      AND history.event_type = 'PAYMENT_CONFIRMED'
                      AND history.event_at <= cycle.created_at
                )
          )
    ) THEN
        RAISE EXCEPTION 'V40 cannot apply: a dunning attempt has no matching historical plan snapshot; inspect attempts and authoritative checkout evidence read-only';
    END IF;
END $$;

ALTER TABLE billing_dunning_attempt ADD COLUMN contract_plan_code VARCHAR(16);
UPDATE billing_dunning_attempt attempt
SET contract_plan_code = contract.plan_code
FROM billing_dunning_cycle cycle
JOIN billing_subscription_contract contract
  ON contract.subscription_id = cycle.subscription_id
 AND contract.tenant_id = cycle.tenant_id
WHERE attempt.cycle_id = cycle.cycle_id
  AND attempt.tenant_id = cycle.tenant_id
  AND contract.recorded_at <= cycle.created_at
  AND contract.amount_minor_units = attempt.contract_amount_minor_units
  AND contract.currency = attempt.contract_currency
  AND contract.terms_version = attempt.contract_terms_version
  AND contract.offer_version = attempt.contract_offer_version;

ALTER TABLE billing_dunning_attempt
    ADD CONSTRAINT ck_dunning_attempt_plan_snapshot CHECK ((
        (contract_amount_minor_units IS NULL AND contract_currency IS NULL
            AND contract_terms_version IS NULL AND contract_offer_version IS NULL
            AND contract_plan_code IS NULL)
        OR (contract_amount_minor_units > 0
            AND contract_currency ~ '^[A-Z]{3}$'
            AND contract_terms_version <> '' AND contract_offer_version <> ''
            AND contract_plan_code IN ('MONTHLY', 'ANNUAL'))
    ) IS TRUE);

CREATE FUNCTION require_authenticated_subscription_contract_source()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    source_matches BOOLEAN;
BEGIN
    EXECUTE format(
        'SELECT EXISTS (
            SELECT 1
            FROM %I.billing_checkout_operation operation
            JOIN %I.billing_event_inbox evidence
              ON evidence.provider_code = operation.provider_code
             AND evidence.provider_account_id = operation.provider_account_id
             AND evidence.provider_reference = operation.provider_reference
            WHERE operation.operation_id = $1
              AND operation.tenant_id = $2
              AND operation.state = ''CONFIRMED''
              AND operation.plan_code = $3
              AND operation.amount_minor_units = $4
              AND operation.currency = $5
              AND operation.terms_version = $6
              AND operation.offer_version = $7
              AND $8 >= operation.updated_at
              AND operation.last_provider_sequence = evidence.provider_sequence
              AND evidence.operation_id = operation.operation_id
              AND evidence.tenant_id = operation.tenant_id
              AND evidence.disposition = ''PENDING''
              AND evidence.outcome = ''CONFIRMED''
              AND evidence.amount_minor_units = $4
              AND evidence.currency = $5
        )', TG_TABLE_SCHEMA, TG_TABLE_SCHEMA)
    INTO source_matches
    USING NEW.source_operation_id, NEW.tenant_id, NEW.plan_code,
          NEW.amount_minor_units, NEW.currency, NEW.terms_version,
          NEW.offer_version, NEW.recorded_at;

    IF source_matches IS NOT TRUE THEN
        RAISE EXCEPTION 'subscription contract terms require matching authenticated checkout evidence';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_subscription_contract_authenticated_source
    BEFORE INSERT OR UPDATE ON billing_subscription_contract
    FOR EACH ROW EXECUTE FUNCTION require_authenticated_subscription_contract_source();

COMMENT ON COLUMN billing_subscription_contract.plan_code IS
    'Plan code from its correlated confirmed checkout operation; mutable subscription plan is not used as contract lineage.';
COMMENT ON COLUMN billing_dunning_attempt.contract_plan_code IS
    'Immutable plan snapshot for this logical dunning attempt; missing historical lineage blocks sending rather than inferring from the current subscription.';
COMMENT ON FUNCTION require_authenticated_subscription_contract_source() IS
    'Allows current subscription contract terms to change only while applying a correlated authenticated CONFIRMED checkout event.';

REVOKE UPDATE ON billing_subscription_contract FROM agendamento_runtime;
GRANT SELECT, INSERT ON billing_subscription_contract TO agendamento_runtime;
GRANT UPDATE (source_operation_id, amount_minor_units, currency, terms_version,
    offer_version, plan_code, recorded_at)
    ON billing_subscription_contract TO agendamento_runtime;
