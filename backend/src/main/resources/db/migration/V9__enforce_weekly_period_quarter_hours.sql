DO $$
DECLARE
    unaligned_period_count BIGINT;
BEGIN
    SELECT count(*)
      INTO unaligned_period_count
      FROM weekly_period
     WHERE mod(extract(minute FROM local_start)::INTEGER, 15) <> 0
        OR extract(second FROM local_start) <> 0
        OR mod(extract(minute FROM local_end)::INTEGER, 15) <> 0
        OR extract(second FROM local_end) <> 0
        OR local_end >= TIME '24:00';

    IF unaligned_period_count > 0 THEN
        RAISE EXCEPTION USING
            MESSAGE = 'V9 cannot apply: weekly_period has boundaries incompatible with quarter-hour rules',
            DETAIL = format('%s weekly period row(s) require an explicit schedule decision.', unaligned_period_count),
            HINT = 'Run the documented preflight query, obtain tenant-admin approval, update the schedule explicitly with CAS, then retry Flyway.';
    END IF;
END
$$;

ALTER TABLE weekly_period
    ADD CONSTRAINT ck_weekly_period_quarter_hour CHECK (
        mod(extract(minute FROM local_start)::INTEGER, 15) = 0
        AND extract(second FROM local_start) = 0
        AND mod(extract(minute FROM local_end)::INTEGER, 15) = 0
        AND extract(second FROM local_end) = 0
        AND local_end < TIME '24:00'
    );
