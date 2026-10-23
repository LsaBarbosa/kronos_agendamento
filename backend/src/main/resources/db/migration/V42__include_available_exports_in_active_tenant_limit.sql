-- Read-only preflight before changing the active-job invariant. Do not choose a winner
-- or rewrite historical jobs automatically; resolve any returned tenant explicitly.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM export_job
        WHERE state IN ('REQUESTED', 'PROCESSING', 'RETRY_WAIT', 'AVAILABLE')
        GROUP BY tenant_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'V42 cannot apply: multiple active export jobs exist for a tenant'
            USING HINT = 'Run scripts/db/preflight-exports-v42.sql. Resolve each tenant explicitly without deleting or rewriting history, then retry Flyway.';
    END IF;
END $$;

DROP INDEX uq_export_one_active_per_tenant;
CREATE UNIQUE INDEX uq_export_one_active_per_tenant
    ON export_job (tenant_id)
    WHERE state IN ('REQUESTED', 'PROCESSING', 'RETRY_WAIT', 'AVAILABLE');
