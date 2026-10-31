-- Read-only prerequisite check for V42. Do not pick a winner or alter rows automatically.
SELECT tenant_id,
       array_agg(export_id ORDER BY created_at, export_id) AS active_export_ids,
       array_agg(state ORDER BY created_at, export_id) AS states,
       count(*) AS active_count
FROM agendamento.export_job
WHERE state IN ('REQUESTED', 'PROCESSING', 'RETRY_WAIT', 'AVAILABLE')
GROUP BY tenant_id
HAVING count(*) > 1
ORDER BY tenant_id;
