CREATE TABLE client_access_phone_limit (
    tenant_id UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE RESTRICT,
    phone_fingerprint BYTEA NOT NULL,
    fingerprint_key_id VARCHAR(64) NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    send_count INTEGER NOT NULL,
    blocked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, phone_fingerprint),
    CONSTRAINT ck_client_access_limit_fingerprint CHECK (octet_length(phone_fingerprint) = 32),
    CONSTRAINT ck_client_access_limit_count CHECK (send_count BETWEEN 0 AND 3)
);

CREATE TABLE client_access_challenge (
    challenge_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE RESTRICT,
    phone_fingerprint BYTEA NOT NULL,
    fingerprint_key_id VARCHAR(64) NOT NULL,
    phone_ciphertext BYTEA NOT NULL,
    phone_key_id VARCHAR(64) NOT NULL,
    eligible_for_access BOOLEAN NOT NULL,
    code_salt BYTEA NOT NULL,
    code_verifier BYTEA NOT NULL,
    code_key_id VARCHAR(64) NOT NULL,
    idempotency_digest BYTEA NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    blocked_until TIMESTAMPTZ,
    delivery_status VARCHAR(20) NOT NULL,
    delivery_reference VARCHAR(128),
    CONSTRAINT uq_client_access_challenge_intent UNIQUE (tenant_id, idempotency_digest),
    CONSTRAINT uq_client_access_challenge_tenant UNIQUE (tenant_id, challenge_id),
    CONSTRAINT ck_client_access_challenge_fingerprint CHECK (octet_length(phone_fingerprint) = 32),
    CONSTRAINT ck_client_access_challenge_ciphertext CHECK (octet_length(phone_ciphertext) >= 29),
    CONSTRAINT ck_client_access_challenge_salt CHECK (octet_length(code_salt) = 16),
    CONSTRAINT ck_client_access_challenge_verifier CHECK (octet_length(code_verifier) = 32),
    CONSTRAINT ck_client_access_challenge_intent_digest CHECK (octet_length(idempotency_digest) = 32),
    CONSTRAINT ck_client_access_challenge_status CHECK (status IN ('ISSUED', 'VERIFIED', 'EXPIRED', 'BLOCKED', 'SUPERSEDED')),
    CONSTRAINT ck_client_access_delivery_status CHECK (delivery_status IN ('PENDING', 'ACCEPTED', 'FAILED', 'UNKNOWN', 'UNAVAILABLE')),
    CONSTRAINT ck_client_access_attempts CHECK (failed_attempts BETWEEN 0 AND 5),
    CONSTRAINT ck_client_access_challenge_expiry CHECK (expires_at > created_at)
);

CREATE TABLE client_access_request_intent (
    tenant_id UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE RESTRICT,
    idempotency_digest BYTEA NOT NULL,
    phone_fingerprint BYTEA NOT NULL,
    fingerprint_key_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, idempotency_digest),
    CONSTRAINT ck_client_access_request_digest CHECK (octet_length(idempotency_digest) = 32),
    CONSTRAINT ck_client_access_request_fingerprint CHECK (octet_length(phone_fingerprint) = 32)
);

CREATE TABLE client_access_send_intent (
    tenant_id UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE RESTRICT,
    phone_fingerprint BYTEA NOT NULL,
    fingerprint_key_id VARCHAR(64) NOT NULL,
    idempotency_digest BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, idempotency_digest),
    CONSTRAINT ck_client_access_send_digest CHECK (octet_length(idempotency_digest) = 32),
    CONSTRAINT ck_client_access_send_fingerprint CHECK (octet_length(phone_fingerprint) = 32)
);
CREATE INDEX ix_client_access_send_window
    ON client_access_send_intent(tenant_id, phone_fingerprint, created_at DESC);

CREATE INDEX ix_client_access_challenge_phone
    ON client_access_challenge(tenant_id, phone_fingerprint, created_at DESC);
CREATE INDEX ix_client_access_challenge_expiry
    ON client_access_challenge(expires_at) WHERE status = 'ISSUED';

CREATE TABLE client_access_session (
    session_digest BYTEA PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE RESTRICT,
    phone_ciphertext BYTEA NOT NULL,
    phone_key_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_client_access_session_digest CHECK (octet_length(session_digest) = 32),
    CONSTRAINT ck_client_access_session_ciphertext CHECK (octet_length(phone_ciphertext) >= 29),
    CONSTRAINT ck_client_access_session_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_client_access_session_expiry
    ON client_access_session(expires_at) WHERE revoked_at IS NULL;

GRANT SELECT, INSERT ON client_access_phone_limit TO agendamento_runtime;
GRANT UPDATE (send_count, window_started_at, blocked_until, updated_at)
    ON client_access_phone_limit TO agendamento_runtime;
GRANT SELECT, INSERT ON client_access_challenge TO agendamento_runtime;
GRANT UPDATE (status, failed_attempts, blocked_until, delivery_status)
    ON client_access_challenge TO agendamento_runtime;
GRANT SELECT, INSERT ON client_access_request_intent TO agendamento_runtime;
GRANT SELECT, INSERT ON client_access_send_intent TO agendamento_runtime;
GRANT SELECT, INSERT ON client_access_session TO agendamento_runtime;
GRANT UPDATE (revoked_at) ON client_access_session TO agendamento_runtime;
