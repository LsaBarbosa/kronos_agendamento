CREATE TABLE notification_intent (
    notification_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    appointment_id UUID NOT NULL,
    appointment_version BIGINT NOT NULL,
    message_type VARCHAR(40) NOT NULL,
    recipient_client_id UUID NOT NULL,
    source_event_id UUID NOT NULL UNIQUE REFERENCES outbox_event(event_id) ON DELETE RESTRICT,
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    external_idempotency_key VARCHAR(80) NOT NULL UNIQUE,
    provider_code VARCHAR(64),
    provider_message_reference VARCHAR(160),
    last_error_code VARCHAR(64),
    last_evidence_sequence BIGINT NOT NULL DEFAULT -1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    accepted_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    CONSTRAINT uq_notification_logical_identity UNIQUE
        (tenant_id, appointment_id, appointment_version, message_type, recipient_client_id),
    CONSTRAINT fk_notification_tenant_appointment FOREIGN KEY (tenant_id, appointment_id)
        REFERENCES appointment(tenant_id, appointment_id) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_tenant_recipient FOREIGN KEY (tenant_id, recipient_client_id)
        REFERENCES client(tenant_id, client_id) ON DELETE RESTRICT,
    CONSTRAINT ck_notification_version CHECK (appointment_version >= 1),
    CONSTRAINT ck_notification_type CHECK
        (message_type IN ('APPOINTMENT_CONFIRMED', 'APPOINTMENT_RESCHEDULED', 'APPOINTMENT_CANCELLED')),
    CONSTRAINT ck_notification_state CHECK
        (state IN ('PENDING', 'CLAIMED', 'RETRY', 'ACCEPTED', 'DELIVERED', 'DELIVERY_FAILED',
                   'FAILED_FINAL', 'OUTCOME_UNKNOWN', 'OBSOLETE')),
    CONSTRAINT ck_notification_attempts CHECK (attempt_count >= 0 AND last_evidence_sequence >= -1),
    CONSTRAINT ck_notification_claim CHECK
        ((state = 'CLAIMED' AND claim_token IS NOT NULL AND lease_until IS NOT NULL) OR
         (state <> 'CLAIMED' AND claim_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_notification_acceptance CHECK
        ((state IN ('ACCEPTED', 'DELIVERED', 'DELIVERY_FAILED') AND accepted_at IS NOT NULL) OR
         (state NOT IN ('ACCEPTED', 'DELIVERED', 'DELIVERY_FAILED') AND accepted_at IS NULL)),
    CONSTRAINT ck_notification_delivery CHECK
        ((state = 'DELIVERED' AND delivered_at IS NOT NULL) OR
         (state <> 'DELIVERED' AND delivered_at IS NULL)),
    CONSTRAINT ck_notification_provider_reference CHECK
        ((provider_code IS NULL AND provider_message_reference IS NULL) OR
         (provider_code ~ '^[a-z0-9][a-z0-9._-]{0,63}$' AND
          provider_message_reference IS NOT NULL AND length(provider_message_reference) BETWEEN 1 AND 160)),
    CONSTRAINT ck_notification_error_code CHECK
        (last_error_code IS NULL OR last_error_code IN
          ('RECIPIENT_UNAVAILABLE', 'TRANSPORT_RETRYABLE', 'TRANSPORT_TERMINAL',
           'TRANSPORT_OUTCOME_UNKNOWN', 'LEASE_EXPIRED_OUTCOME_UNKNOWN'))
);

CREATE UNIQUE INDEX uq_notification_provider_reference
    ON notification_intent(provider_code, provider_message_reference)
    WHERE provider_code IS NOT NULL;
CREATE INDEX ix_notification_ready
    ON notification_intent(next_attempt_at, created_at, notification_id)
    WHERE state IN ('PENDING', 'RETRY');
CREATE INDEX ix_notification_expired_lease
    ON notification_intent(lease_until, notification_id)
    WHERE state = 'CLAIMED';
CREATE INDEX ix_notification_tenant_recent
    ON notification_intent(tenant_id, created_at DESC, notification_id DESC);

CREATE TABLE notification_attempt (
    attempt_id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notification_intent(notification_id) ON DELETE RESTRICT,
    attempt_number INTEGER NOT NULL,
    claim_token UUID NOT NULL UNIQUE,
    state VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_notification_attempt_number UNIQUE(notification_id, attempt_number),
    CONSTRAINT ck_notification_attempt_number CHECK (attempt_number >= 1),
    CONSTRAINT ck_notification_attempt_state CHECK
        (state IN ('STARTED', 'ACCEPTED', 'RETRYABLE_NO_EFFECT', 'FAILED_FINAL', 'OUTCOME_UNKNOWN', 'OBSOLETE')),
    CONSTRAINT ck_notification_attempt_completion CHECK
        ((state = 'STARTED' AND completed_at IS NULL) OR
         (state <> 'STARTED' AND completed_at IS NOT NULL)),
    CONSTRAINT ck_notification_attempt_error CHECK
        (error_code IS NULL OR error_code IN
          ('RECIPIENT_UNAVAILABLE', 'TRANSPORT_RETRYABLE', 'TRANSPORT_TERMINAL',
           'TRANSPORT_OUTCOME_UNKNOWN', 'LEASE_EXPIRED_OUTCOME_UNKNOWN'))
);
CREATE INDEX ix_notification_attempt_recent
    ON notification_attempt(notification_id, attempt_number DESC);

CREATE TABLE notification_delivery_evidence (
    provider_code VARCHAR(64) NOT NULL,
    provider_event_id VARCHAR(160) NOT NULL,
    notification_id UUID NOT NULL REFERENCES notification_intent(notification_id) ON DELETE RESTRICT,
    provider_sequence BIGINT NOT NULL,
    delivery_state VARCHAR(24) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    admitted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_notification_delivery_evidence PRIMARY KEY(provider_code, provider_event_id),
    CONSTRAINT ck_notification_evidence_provider CHECK (provider_code ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT ck_notification_evidence_event CHECK (length(provider_event_id) BETWEEN 1 AND 160),
    CONSTRAINT ck_notification_evidence_sequence CHECK (provider_sequence >= 0),
    CONSTRAINT ck_notification_evidence_state CHECK (delivery_state IN ('DELIVERED', 'FAILED'))
);
CREATE INDEX ix_notification_evidence_intent
    ON notification_delivery_evidence(notification_id, provider_sequence DESC);

REVOKE ALL ON notification_intent, notification_attempt, notification_delivery_evidence FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON notification_intent TO agendamento_runtime;
GRANT SELECT, INSERT, UPDATE ON notification_attempt TO agendamento_runtime;
GRANT SELECT, INSERT ON notification_delivery_evidence TO agendamento_runtime;
