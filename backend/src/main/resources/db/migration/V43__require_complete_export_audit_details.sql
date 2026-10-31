DO $$
DECLARE
    old_check TEXT;
    old_expr TEXT;
    invalid_count BIGINT;
BEGIN
    -- Read-only preflight. Existing immutable audit rows are never repaired here.
    SELECT count(*) INTO invalid_count
    FROM audit_event
    WHERE action IN ('EXPORT_REQUESTED', 'EXPORT_AVAILABLE', 'EXPORT_DOWNLOADED', 'EXPORT_EXPIRED')
      AND (CASE action
        WHEN 'EXPORT_REQUESTED' THEN (
            details->'changed_fields' = '["export"]'::jsonb
            AND details->>'category' = 'APPOINTMENTS'
            AND details->>'format' IN ('CSV', 'PDF')
            AND details->'version_before' = '0'::jsonb
            AND details->'version_after' = '1'::jsonb
            AND details - ARRAY['changed_fields','category','format','version_before','version_after']::text[] = '{}'::jsonb)
        WHEN 'EXPORT_AVAILABLE' THEN (
            details->'changed_fields' = '["export"]'::jsonb
            AND jsonb_typeof(details->'version_before') = 'number'
            AND jsonb_typeof(details->'version_after') = 'number'
            AND jsonb_typeof(details->'row_count') = 'number'
            AND details - ARRAY['changed_fields','version_before','version_after','row_count']::text[] = '{}'::jsonb)
        WHEN 'EXPORT_DOWNLOADED' THEN details = '{"changed_fields":["export_download"]}'::jsonb
        WHEN 'EXPORT_EXPIRED' THEN (
            details->'changed_fields' = '["export"]'::jsonb
            AND jsonb_typeof(details->'version_before') = 'number'
            AND jsonb_typeof(details->'version_after') = 'number'
            AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb)
      END) IS NOT TRUE;

    IF invalid_count > 0 THEN
        RAISE EXCEPTION 'V43 preflight found % incompatible export audit event(s); resolve through an approved append-only remediation process before upgrading', invalid_count;
    END IF;

    SELECT pg_get_constraintdef(oid) INTO old_check
    FROM pg_constraint
    WHERE conrelid = 'audit_event'::regclass AND conname = 'ck_audit_details_allowlist';
    old_expr := substring(old_check FROM '(?s)^CHECK \((.*)\)$');
    IF old_expr IS NULL THEN
        RAISE EXCEPTION 'V43 cannot preserve the existing audit detail allowlist';
    END IF;

    ALTER TABLE audit_event DROP CONSTRAINT ck_audit_details_allowlist;
    EXECUTE format($ddl$
        ALTER TABLE audit_event ADD CONSTRAINT ck_audit_details_allowlist
        CHECK (CASE WHEN action IN ('EXPORT_REQUESTED','EXPORT_AVAILABLE','EXPORT_DOWNLOADED','EXPORT_EXPIRED')
            THEN (CASE action
            WHEN 'EXPORT_REQUESTED' THEN (
                details->'changed_fields' = '["export"]'::jsonb
                AND details->>'category' = 'APPOINTMENTS' AND details->>'format' IN ('CSV','PDF')
                AND details->'version_before' = '0'::jsonb AND details->'version_after' = '1'::jsonb
                AND details - ARRAY['changed_fields','category','format','version_before','version_after']::text[] = '{}'::jsonb)
            WHEN 'EXPORT_AVAILABLE' THEN (
                details->'changed_fields' = '["export"]'::jsonb
                AND jsonb_typeof(details->'version_before') = 'number'
                AND jsonb_typeof(details->'version_after') = 'number'
                AND jsonb_typeof(details->'row_count') = 'number'
                AND details - ARRAY['changed_fields','version_before','version_after','row_count']::text[] = '{}'::jsonb)
            WHEN 'EXPORT_DOWNLOADED' THEN details = '{"changed_fields":["export_download"]}'::jsonb
            WHEN 'EXPORT_EXPIRED' THEN (
                details->'changed_fields' = '["export"]'::jsonb
                AND jsonb_typeof(details->'version_before') = 'number'
                AND jsonb_typeof(details->'version_after') = 'number'
                AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb)
            END) IS TRUE
            ELSE (%s)
        END)
    $ddl$, old_expr);
END $$;

