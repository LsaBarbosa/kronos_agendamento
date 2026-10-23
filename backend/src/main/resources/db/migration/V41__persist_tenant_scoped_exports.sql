CREATE TABLE export_job (
    export_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant (tenant_id),
    requested_by_admin_id UUID NOT NULL REFERENCES admin_user (user_id),
    operation_id UUID NOT NULL UNIQUE REFERENCES idempotency_record (operation_id),
    format VARCHAR(8) NOT NULL,
    category VARCHAR(32) NOT NULL,
    filters JSONB NOT NULL,
    snapshot_at TIMESTAMPTZ NOT NULL,
    snapshot_ciphertext BYTEA NOT NULL,
    snapshot_key_id VARCHAR(64) NOT NULL,
    snapshot_digest CHAR(64) NOT NULL,
    row_count INTEGER NOT NULL,
    state VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    artifact_key VARCHAR(160),
    artifact_key_id VARCHAR(64),
    artifact_digest CHAR(64),
    artifact_size BIGINT,
    available_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    failure_code VARCHAR(64),
    artifact_deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_export_tenant_id UNIQUE (tenant_id, export_id),
    CONSTRAINT ck_export_format CHECK (format IN ('CSV', 'PDF')),
    CONSTRAINT ck_export_category CHECK (category IN ('APPOINTMENTS')),
    CONSTRAINT ck_export_filters_object CHECK (jsonb_typeof(filters) = 'object'),
    CONSTRAINT ck_export_snapshot CHECK (
        octet_length(snapshot_ciphertext) >= 29
        AND snapshot_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'
        AND snapshot_digest ~ '^[0-9a-f]{64}$'
        AND row_count >= 0
    ),
    CONSTRAINT ck_export_state CHECK (state IN (
        'REQUESTED', 'PROCESSING', 'RETRY_WAIT', 'AVAILABLE', 'FAILED_FINAL', 'EXPIRED'
    )),
    CONSTRAINT ck_export_version_attempt CHECK (version >= 1 AND attempt_count >= 0),
    CONSTRAINT ck_export_claim CHECK (
        (state = 'PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state <> 'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_export_artifact_state CHECK ((
        (state = 'AVAILABLE' AND artifact_key IS NOT NULL AND artifact_key_id IS NOT NULL
            AND artifact_digest ~ '^[0-9a-f]{64}$' AND artifact_size >= 0
            AND available_at IS NOT NULL AND expires_at > available_at
            AND expires_at <= available_at + interval '30 minutes')
        OR (state = 'EXPIRED' AND artifact_key IS NOT NULL AND artifact_key_id IS NOT NULL
            AND artifact_digest ~ '^[0-9a-f]{64}$' AND artifact_size >= 0
            AND available_at IS NOT NULL AND expires_at > available_at
            AND expires_at <= available_at + interval '30 minutes')
        OR (state NOT IN ('AVAILABLE', 'EXPIRED') AND artifact_key IS NOT NULL
            AND artifact_key_id IS NULL AND artifact_digest IS NULL AND artifact_size IS NULL
            AND available_at IS NULL AND expires_at IS NULL
            AND (artifact_deleted_at IS NULL OR state = 'FAILED_FINAL'))
    ) IS TRUE),
    CONSTRAINT ck_export_failure CHECK (
        failure_code IS NULL OR failure_code IN (
            'ENCRYPTION_UNAVAILABLE', 'SNAPSHOT_INVALID',
            'GENERATION_FAILED', 'STORAGE_TRANSIENT', 'STORAGE_REJECTED', 'WORKER_LEASE_EXHAUSTED'
        )
    ),
    CONSTRAINT ck_export_delete_time CHECK (
        artifact_deleted_at IS NULL OR state IN ('EXPIRED', 'FAILED_FINAL')
    )
);
ALTER TABLE export_job ADD CONSTRAINT ck_export_artifact_key CHECK (
    artifact_key = tenant_id::text || '/exports/' || export_id::text
);

CREATE UNIQUE INDEX uq_export_one_active_per_tenant
    ON export_job (tenant_id)
    WHERE state IN ('REQUESTED', 'PROCESSING', 'RETRY_WAIT');
CREATE INDEX ix_export_ready
    ON export_job (next_attempt_at, created_at, export_id)
    WHERE state IN ('REQUESTED', 'RETRY_WAIT');
CREATE INDEX ix_export_expired_lease
    ON export_job (lease_until, export_id)
    WHERE state = 'PROCESSING';
CREATE INDEX ix_export_expiration
    ON export_job (expires_at, export_id)
    WHERE state = 'AVAILABLE';
CREATE INDEX ix_export_cleanup
    ON export_job (updated_at, export_id)
    WHERE state IN ('EXPIRED', 'FAILED_FINAL') AND artifact_deleted_at IS NULL;

CREATE TABLE export_download_handle (
    handle_digest CHAR(64) PRIMARY KEY,
    tenant_id UUID NOT NULL,
    export_id UUID NOT NULL,
    issued_to_admin_id UUID NOT NULL REFERENCES admin_user (user_id),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_export_handle_job FOREIGN KEY (tenant_id, export_id)
        REFERENCES export_job (tenant_id, export_id),
    CONSTRAINT ck_export_handle_digest CHECK (handle_digest ~ '^[0-9a-f]{64}$')
);
CREATE INDEX ix_export_handle_job ON export_download_handle (tenant_id, export_id, expires_at);

REVOKE ALL ON export_job, export_download_handle FROM PUBLIC;
GRANT SELECT, INSERT ON export_job TO agendamento_runtime;
GRANT UPDATE (state, version, attempt_count, next_attempt_at, claim_token, lease_until,
    artifact_key, artifact_key_id, artifact_digest, artifact_size, available_at,
    expires_at, failure_code, artifact_deleted_at, updated_at)
    ON export_job TO agendamento_runtime;
GRANT SELECT, INSERT ON export_download_handle TO agendamento_runtime;

DO $$
DECLARE old_check TEXT; old_expr TEXT;
BEGIN
    SELECT pg_get_constraintdef(oid) INTO old_check FROM pg_constraint
    WHERE conrelid = 'audit_event'::regclass AND conname = 'ck_audit_action';
    old_expr := substring(old_check FROM '(?s)^CHECK \((.*)\)$');
    IF old_expr IS NULL THEN RAISE EXCEPTION 'V41 cannot preserve audit action allowlist'; END IF;
    ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action;
    EXECUTE format('ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action CHECK ((%s) OR action IN (''EXPORT_REQUESTED'',''EXPORT_AVAILABLE'',''EXPORT_DOWNLOADED'',''EXPORT_EXPIRED''))', old_expr);

    SELECT pg_get_constraintdef(oid) INTO old_check FROM pg_constraint
    WHERE conrelid = 'audit_event'::regclass AND conname = 'ck_audit_actor_ref';
    old_expr := substring(old_check FROM '(?s)^CHECK \((.*)\)$');
    IF old_expr IS NULL THEN RAISE EXCEPTION 'V41 cannot preserve audit actor references'; END IF;
    ALTER TABLE audit_event DROP CONSTRAINT ck_audit_actor_ref;
    EXECUTE format('ALTER TABLE audit_event ADD CONSTRAINT ck_audit_actor_ref CHECK ((%s) OR (actor_type = ''SYSTEM'' AND actor_ref = ''EXPORT_LIFECYCLE''))', old_expr);

    SELECT pg_get_constraintdef(oid) INTO old_check FROM pg_constraint
    WHERE conrelid = 'audit_event'::regclass AND conname = 'ck_audit_action_actor';
    old_expr := substring(old_check FROM '(?s)^CHECK \((.*)\)$');
    IF old_expr IS NULL THEN RAISE EXCEPTION 'V41 cannot preserve audit action actors'; END IF;
    ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action_actor;
    EXECUTE format($ddl$ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action_actor CHECK ((%s) OR
        (action IN ('EXPORT_REQUESTED','EXPORT_DOWNLOADED') AND actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN') OR
        (action IN ('EXPORT_AVAILABLE','EXPORT_EXPIRED') AND actor_type = 'SYSTEM' AND actor_ref = 'EXPORT_LIFECYCLE') OR
        (action = 'EXPORT_EXPIRED' AND actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN'))$ddl$, old_expr);

    SELECT pg_get_constraintdef(oid) INTO old_check FROM pg_constraint
    WHERE conrelid = 'audit_event'::regclass AND conname = 'ck_audit_details_allowlist';
    old_expr := substring(old_check FROM '(?s)^CHECK \((.*)\)$');
    IF old_expr IS NULL THEN RAISE EXCEPTION 'V41 cannot preserve audit detail allowlist'; END IF;
    ALTER TABLE audit_event DROP CONSTRAINT ck_audit_details_allowlist;
    EXECUTE format($ddl$ALTER TABLE audit_event ADD CONSTRAINT ck_audit_details_allowlist CHECK ((%s) OR
        (action = 'EXPORT_REQUESTED' AND details->'changed_fields' = '["export"]'::jsonb
            AND details->>'category' = 'APPOINTMENTS' AND details->>'format' IN ('CSV','PDF')
            AND details->'version_before' = '0'::jsonb AND details->'version_after' = '1'::jsonb
            AND details - ARRAY['changed_fields','category','format','version_before','version_after']::text[] = '{}'::jsonb) OR
        (action = 'EXPORT_AVAILABLE' AND details->'changed_fields' = '["export"]'::jsonb
            AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
            AND jsonb_typeof(details->'row_count') = 'number'
            AND details - ARRAY['changed_fields','version_before','version_after','row_count']::text[] = '{}'::jsonb) OR
        (action = 'EXPORT_DOWNLOADED' AND details = '{"changed_fields":["export_download"]}'::jsonb) OR
        (action = 'EXPORT_EXPIRED' AND details->'changed_fields' = '["export"]'::jsonb
            AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
            AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb))$ddl$, old_expr);

    SELECT pg_get_constraintdef(oid) INTO old_check FROM pg_constraint
    WHERE conrelid = 'outbox_event'::regclass AND conname = 'ck_outbox_payload_allowlist';
    old_expr := substring(old_check FROM '(?s)^CHECK \((.*)\)$');
    IF old_expr IS NULL THEN RAISE EXCEPTION 'V41 cannot preserve outbox payload allowlist'; END IF;
    ALTER TABLE outbox_event DROP CONSTRAINT ck_outbox_payload_allowlist;
    EXECUTE format($ddl$ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_payload_allowlist CHECK ((%s) OR
        (event_type IN ('ExportRequested','ExportAvailable','ExportExpired') AND aggregate_type = 'EXPORT'
            AND payload ?& ARRAY['exportId','version'] AND payload - ARRAY['exportId','version']::text[] = '{}'::jsonb
            AND jsonb_typeof(payload->'exportId') = 'string' AND jsonb_typeof(payload->'version') = 'number'))$ddl$, old_expr);
END $$;
COMMENT ON TABLE export_job IS
    'Tenant-scoped immutable export snapshot and artifact state; application encrypts all dataset/objects before persistence.';
COMMENT ON COLUMN export_job.snapshot_ciphertext IS
    'AES-256-GCM ciphertext of the immutable row snapshot; plaintext is never persisted.';
COMMENT ON COLUMN export_download_handle.handle_digest IS
    'SHA-256 of an opaque locator. It is not authorization; current OIDC membership is checked on every download.';
