CREATE TABLE idempotency_record (
    tenant_id UUID NOT NULL,
    command_scope VARCHAR(96) NOT NULL,
    key_digest CHAR(64) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    operation_id UUID NOT NULL UNIQUE,
    state VARCHAR(32) NOT NULL,
    result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (tenant_id, command_scope, key_digest),
    CONSTRAINT ck_idempotency_scope CHECK (command_scope ~ '^[a-z0-9][a-z0-9._-]{0,95}$'),
    CONSTRAINT ck_idempotency_digests CHECK (key_digest ~ '^[0-9a-f]{64}$' AND request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_idempotency_result_state CHECK (
        (state = 'IN_PROGRESS' AND result IS NULL AND completed_at IS NULL) OR
        (state = 'COMPLETED' AND result IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE INDEX ix_idempotency_operation ON idempotency_record (operation_id);

CREATE TABLE outbox_event (
    event_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(48) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    schema_version INTEGER NOT NULL,
    operation_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    payload JSONB NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    reprocess_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    delivered_at TIMESTAMPTZ,
    CONSTRAINT uq_outbox_operation_type UNIQUE (operation_id, event_type),
    CONSTRAINT ck_outbox_aggregate_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_outbox_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_outbox_state CHECK (state IN ('PENDING', 'CLAIMED', 'RETRY', 'DELIVERED', 'DEAD_LETTER', 'RECONCILIATION_REQUIRED')),
    CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_reprocess_count CHECK (reprocess_count >= 0),
    CONSTRAINT ck_outbox_claim CHECK (
        (state = 'CLAIMED' AND claim_token IS NOT NULL AND lease_until IS NOT NULL) OR
        (state <> 'CLAIMED' AND claim_token IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_outbox_delivery_time CHECK (
        (state = 'DELIVERED' AND delivered_at IS NOT NULL) OR
        (state <> 'DELIVERED' AND delivered_at IS NULL)
    ),
    CONSTRAINT ck_outbox_error_code CHECK (
        last_error_code IS NULL OR last_error_code IN (
            'DESTINATION_RETRYABLE', 'DESTINATION_TERMINAL', 'DESTINATION_OUTCOME_UNKNOWN'
        )
    ),
    CONSTRAINT ck_outbox_payload_allowlist CHECK (
        event_type = 'TENANT_RENAMED' AND aggregate_type = 'TENANT'
        AND payload ?& ARRAY['tenantId', 'version']
        AND payload - ARRAY['tenantId', 'version']::text[] = '{}'::jsonb
        AND jsonb_typeof(payload->'tenantId') = 'string'
        AND jsonb_typeof(payload->'version') = 'number'
    )
);

CREATE INDEX ix_outbox_ready ON outbox_event (next_attempt_at, occurred_at, event_id)
    WHERE state IN ('PENDING', 'RETRY');
CREATE INDEX ix_outbox_expired_lease ON outbox_event (lease_until, occurred_at, event_id)
    WHERE state = 'CLAIMED';
CREATE INDEX ix_outbox_tenant_aggregate ON outbox_event (tenant_id, aggregate_type, aggregate_id, occurred_at DESC);

CREATE TABLE processed_event (
    consumer_name VARCHAR(96) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_processed_event PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT ck_processed_consumer CHECK (consumer_name ~ '^[a-z0-9][a-z0-9._-]{0,95}$')
);

REVOKE ALL ON idempotency_record, outbox_event, processed_event FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON idempotency_record TO agendamento_runtime;
GRANT SELECT, INSERT, UPDATE ON outbox_event TO agendamento_runtime;
GRANT SELECT, INSERT ON processed_event TO agendamento_runtime;
