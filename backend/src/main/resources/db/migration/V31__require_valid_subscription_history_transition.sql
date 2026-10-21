DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM subscription_history
         WHERE NOT (
             (event_type = 'TRIAL_GRANTED'
                 AND from_status IS NULL
                 AND to_status = 'TRIALING'
                 AND version = 1)
             OR
             (event_type = 'TRIAL_EXPIRED'
                 AND from_status = 'TRIALING'
                 AND to_status = 'SUSPENDED'
                 AND version = 2)
         ) IS TRUE
    ) THEN
        RAISE EXCEPTION 'V31 cannot apply: subscription_history contains incompatible transitions; inspect scripts/db/preflight-subscription-history-v31.sql';
    END IF;
END;
$$;

ALTER TABLE subscription_history
    DROP CONSTRAINT ck_subscription_history_transition;

ALTER TABLE subscription_history
    ADD CONSTRAINT ck_subscription_history_transition CHECK ((
        (event_type = 'TRIAL_GRANTED'
            AND from_status IS NULL
            AND to_status = 'TRIALING'
            AND version = 1)
        OR
        (event_type = 'TRIAL_EXPIRED'
            AND from_status = 'TRIALING'
            AND to_status = 'SUSPENDED'
            AND version = 2)
    ) IS TRUE);
