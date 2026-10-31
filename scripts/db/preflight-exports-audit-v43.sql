-- Read-only preflight for V43. Any returned row must be investigated; do not
-- UPDATE, DELETE, or synthesize historical audit details to make the migration pass.
SELECT event_id, tenant_id, action, target_type, target_id, recorded_at
FROM agendamento.audit_event
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
  END) IS NOT TRUE
ORDER BY recorded_at, event_id;
