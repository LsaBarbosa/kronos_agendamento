ALTER TABLE idempotency_record
    ALTER COLUMN request_digest TYPE VARCHAR(128)
    USING rtrim(request_digest::text)::VARCHAR(128);

ALTER TABLE idempotency_record
    DROP CONSTRAINT ck_idempotency_digests;

ALTER TABLE idempotency_record
    ADD CONSTRAINT ck_idempotency_digests CHECK (
        key_digest ~ '^[0-9a-f]{64}$'
        AND (
            request_digest ~ '^[0-9a-f]{64}$'
            OR request_digest ~ '^hmac-sha256:v2:[A-Za-z0-9_-]{1,32}:[0-9a-f]{64}$'
        )
    );

CREATE TABLE public_booking_intent (
    tenant_id UUID NOT NULL REFERENCES tenant (tenant_id),
    command_scope VARCHAR(96) NOT NULL,
    key_digest CHAR(64) NOT NULL,
    recovery_digest CHAR(64) NOT NULL,
    request_digest VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_public_booking_intent
        PRIMARY KEY (tenant_id, command_scope, key_digest),
    CONSTRAINT uq_public_booking_recovery_capability UNIQUE (recovery_digest),
    CONSTRAINT ck_public_booking_intent_scope
        CHECK (command_scope = 'appointments.public.create.v1'),
    CONSTRAINT ck_public_booking_intent_digests CHECK (
        key_digest ~ '^[0-9a-f]{64}$'
        AND recovery_digest ~ '^[0-9a-f]{64}$'
        AND request_digest ~ '^hmac-sha256:v2:[A-Za-z0-9_-]{1,32}:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_public_booking_intent_expiry CHECK (expires_at > created_at)
);

REVOKE ALL ON public_booking_intent FROM PUBLIC;
GRANT SELECT, INSERT ON public_booking_intent TO agendamento_runtime;
