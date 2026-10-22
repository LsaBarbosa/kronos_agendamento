-- Read-only preflight: fail closed instead of repairing or inventing subscription history.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM subscription
        WHERE (
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
                AND ((trial_started_at IS NULL AND trial_ends_at IS NULL)
                    OR (trial_started_at IS NOT NULL
                        AND trial_ends_at = trial_started_at + INTERVAL '336 hours'))
                AND version >= 1)
        ) IS NOT TRUE
    ) THEN
        RAISE EXCEPTION 'V34 cannot apply: incompatible legacy subscription state';
    END IF;
END $$;

ALTER TABLE subscription
    DROP CONSTRAINT ck_subscription_state,
    ADD CONSTRAINT ck_subscription_state CHECK (((
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
            AND ((trial_started_at IS NULL AND trial_ends_at IS NULL)
                OR (trial_started_at IS NOT NULL
                    AND trial_ends_at = trial_started_at + INTERVAL '336 hours'))
            AND version >= 1)
    ) IS TRUE));
