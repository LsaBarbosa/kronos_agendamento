ALTER TABLE schedule_exception_period
    ADD COLUMN local_start_offset_seconds INTEGER,
    ADD COLUMN local_end_offset_seconds INTEGER,
    ADD CONSTRAINT ck_schedule_exception_period_start_offset CHECK (
        local_start_offset_seconds IS NULL OR
        (abs(local_start_offset_seconds) <= 64800 AND mod(local_start_offset_seconds, 60) = 0)
    ),
    ADD CONSTRAINT ck_schedule_exception_period_end_offset CHECK (
        local_end_offset_seconds IS NULL OR
        (abs(local_end_offset_seconds) <= 64800 AND mod(local_end_offset_seconds, 60) = 0)
    );

CREATE FUNCTION count_valid_local_time_offsets(local_value TIMESTAMP, zone_value TEXT)
RETURNS INTEGER
LANGUAGE SQL
STABLE
STRICT
SET search_path = pg_catalog
AS $$
    WITH sampled_offsets AS (
        SELECT DISTINCT
            (sample_instant AT TIME ZONE zone_value)
            - (sample_instant AT TIME ZONE 'UTC') AS offset_value
        FROM generate_series(
            (local_value AT TIME ZONE 'UTC') - INTERVAL '18 hours',
            (local_value AT TIME ZONE 'UTC') + INTERVAL '18 hours',
            INTERVAL '1 minute'
        ) AS samples(sample_instant)
    )
    SELECT count(*)::INTEGER
    FROM sampled_offsets
    WHERE ((local_value AT TIME ZONE 'UTC') - offset_value) AT TIME ZONE zone_value = local_value
$$;

CREATE FUNCTION validate_schedule_exception_period_instants() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    target_date DATE;
    target_zone TEXT;
    target_start TIMESTAMP;
    target_end TIMESTAMP;
    new_start TIMESTAMPTZ;
    new_end TIMESTAMPTZ;
    overlap_exists BOOLEAN;
    valid_offset_count INTEGER;
BEGIN
    EXECUTE format(
        'SELECT e.local_date, t.timezone
           FROM %I.schedule_exception e
           JOIN %I.tenant t USING (tenant_id)
          WHERE e.tenant_id = $1 AND e.exception_id = $2
          FOR UPDATE OF e',
        TG_TABLE_SCHEMA,
        TG_TABLE_SCHEMA)
      INTO target_date, target_zone
      USING NEW.tenant_id, NEW.exception_id;
    IF target_date IS NULL THEN
        RAISE EXCEPTION 'schedule exception does not exist'
            USING ERRCODE = '23503', CONSTRAINT = 'fk_schedule_exception_period_parent';
    END IF;

    target_start := target_date + NEW.local_start;
    target_end := target_date + NEW.local_end;
    IF NEW.local_start_offset_seconds IS NULL THEN
        EXECUTE format('SELECT %I.count_valid_local_time_offsets($1, $2)', TG_TABLE_SCHEMA)
            INTO valid_offset_count USING target_start, target_zone;
        IF valid_offset_count > 1 THEN
            RAISE EXCEPTION 'ambiguous local start requires an explicit offset'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_schedule_exception_period_start_offset_required';
        END IF;
    END IF;
    IF NEW.local_end_offset_seconds IS NULL THEN
        EXECUTE format('SELECT %I.count_valid_local_time_offsets($1, $2)', TG_TABLE_SCHEMA)
            INTO valid_offset_count USING target_end, target_zone;
        IF valid_offset_count > 1 THEN
            RAISE EXCEPTION 'ambiguous local end requires an explicit offset'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_schedule_exception_period_end_offset_required';
        END IF;
    END IF;

    new_start := CASE WHEN NEW.local_start_offset_seconds IS NULL
        THEN target_start AT TIME ZONE target_zone
        ELSE (target_start AT TIME ZONE 'UTC')
             - make_interval(secs => NEW.local_start_offset_seconds) END;
    new_end := CASE WHEN NEW.local_end_offset_seconds IS NULL
        THEN target_end AT TIME ZONE target_zone
        ELSE (target_end AT TIME ZONE 'UTC')
             - make_interval(secs => NEW.local_end_offset_seconds) END;
    IF (new_start AT TIME ZONE target_zone) <> target_start
       OR (new_end AT TIME ZONE target_zone) <> target_end THEN
        RAISE EXCEPTION 'special period local boundary does not resolve in tenant zone'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_schedule_exception_period_zone_offset';
    END IF;
    IF new_start >= new_end THEN
        RAISE EXCEPTION 'special period instants are not ordered'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_schedule_exception_period_instant_order';
    END IF;

    EXECUTE format(
        'SELECT EXISTS (
             SELECT 1
               FROM %I.schedule_exception_period other
              WHERE other.tenant_id = $1 AND other.exception_id = $2
                AND (NOT $3 OR other.local_start <> $4 OR other.local_end <> $5)
                AND $6 < CASE WHEN other.local_end_offset_seconds IS NULL
                    THEN ($7 + other.local_end) AT TIME ZONE $8
                    ELSE (($7 + other.local_end) AT TIME ZONE ''UTC'')
                         - make_interval(secs => other.local_end_offset_seconds) END
                AND (CASE WHEN other.local_start_offset_seconds IS NULL
                    THEN ($7 + other.local_start) AT TIME ZONE $8
                    ELSE (($7 + other.local_start) AT TIME ZONE ''UTC'')
                         - make_interval(secs => other.local_start_offset_seconds) END) < $9
         )',
        TG_TABLE_SCHEMA)
      INTO overlap_exists
      USING NEW.tenant_id,
            NEW.exception_id,
            TG_OP = 'UPDATE',
            CASE WHEN TG_OP = 'UPDATE' THEN OLD.local_start ELSE NULL END,
            CASE WHEN TG_OP = 'UPDATE' THEN OLD.local_end ELSE NULL END,
            new_start,
            target_date,
            target_zone,
            new_end;
    IF overlap_exists THEN
        RAISE EXCEPTION 'special periods overlap as instants'
            USING ERRCODE = '23P01', CONSTRAINT = 'ex_schedule_exception_period_no_instant_overlap';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_schedule_exception_period_instants
BEFORE INSERT OR UPDATE ON schedule_exception_period
FOR EACH ROW EXECUTE FUNCTION validate_schedule_exception_period_instants();
