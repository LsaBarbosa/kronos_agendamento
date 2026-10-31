ALTER TABLE subscription_history
    DROP CONSTRAINT ck_subscription_history_transition,
    ADD CONSTRAINT ck_subscription_history_transition CHECK ((
        (event_type = 'TRIAL_GRANTED' AND from_status IS NULL
            AND to_status = 'TRIALING' AND version = 1)
        OR (event_type = 'TRIAL_EXPIRED' AND from_status = 'TRIALING'
            AND to_status = 'SUSPENDED' AND version = 2)
        OR (event_type = 'PAYMENT_CONFIRMED' AND to_status = 'ACTIVE'
            AND ((from_status IS NULL AND version = 1)
                OR (from_status = 'TRIALING' AND version = 2)
                OR (from_status = 'PAST_DUE' AND version >= 3)
                OR (from_status = 'SUSPENDED' AND version >= 3)
                OR (from_status = 'ACTIVE' AND version >= 2)))
        OR (event_type = 'PAYMENT_PAST_DUE' AND from_status = 'ACTIVE'
            AND to_status = 'PAST_DUE' AND version >= 2)
        OR (event_type = 'PAYMENT_SUSPENDED' AND from_status = 'PAST_DUE'
            AND to_status = 'SUSPENDED' AND version >= 3)
    ) IS TRUE);

DO $$
DECLARE old_check TEXT; old_expr TEXT;
BEGIN
    SELECT pg_get_constraintdef(oid) INTO old_check
    FROM pg_constraint
    WHERE conrelid = 'outbox_event'::regclass
      AND conname = 'ck_outbox_payload_allowlist';
    old_expr := substring(old_check FROM '(?s)^CHECK \((.*)\)$');
    IF old_expr IS NULL THEN
        RAISE EXCEPTION 'V39 cannot preserve ck_outbox_payload_allowlist';
    END IF;
    ALTER TABLE outbox_event DROP CONSTRAINT ck_outbox_payload_allowlist;
    EXECUTE format($sql$
      ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_payload_allowlist CHECK (((%s) OR
        (event_type = 'SubscriptionPeriodExtended'
          AND aggregate_type = 'SUBSCRIPTION'
          AND payload ?& ARRAY['subscriptionId','version']
          AND payload - ARRAY['subscriptionId','version']::text[] = '{}'::jsonb
          AND jsonb_typeof(payload->'subscriptionId') = 'string'
          AND jsonb_typeof(payload->'version') = 'number'
          AND payload->'version' = to_jsonb(aggregate_version))) IS TRUE)
    $sql$, old_expr);
END $$;

COMMENT ON CONSTRAINT ck_subscription_history_transition ON subscription_history IS
    'Payment-confirmed ACTIVE→ACTIVE is an explicit monotonic paid-period extension; all other history transitions remain constrained.';
