CREATE TABLE notification_evidence_inbox (
    provider_code VARCHAR(64) NOT NULL,
    provider_event_id VARCHAR(160) NOT NULL,
    provider_message_reference VARCHAR(160) NOT NULL,
    notification_id UUID REFERENCES notification_intent(notification_id) ON DELETE RESTRICT,
    provider_sequence BIGINT NOT NULL,
    delivery_state VARCHAR(24) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    admitted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_notification_evidence_inbox PRIMARY KEY(provider_code, provider_event_id),
    CONSTRAINT ck_notification_inbox_provider CHECK (provider_code ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT ck_notification_inbox_event CHECK (length(provider_event_id) BETWEEN 1 AND 160),
    CONSTRAINT ck_notification_inbox_reference CHECK
        (length(provider_message_reference) BETWEEN 1 AND 160),
    CONSTRAINT ck_notification_inbox_sequence CHECK (provider_sequence >= 0),
    CONSTRAINT ck_notification_inbox_state CHECK (delivery_state IN ('DELIVERED', 'FAILED'))
);

CREATE INDEX ix_notification_evidence_pending_correlation
    ON notification_evidence_inbox(provider_code, provider_message_reference, provider_sequence,
                                   occurred_at, provider_event_id)
    WHERE notification_id IS NULL;

INSERT INTO notification_evidence_inbox
    (provider_code, provider_event_id, provider_message_reference, notification_id,
     provider_sequence, delivery_state, occurred_at, admitted_at)
SELECT e.provider_code, e.provider_event_id, n.provider_message_reference, e.notification_id,
       e.provider_sequence, e.delivery_state, e.occurred_at, e.admitted_at
FROM notification_delivery_evidence e
JOIN notification_intent n ON n.notification_id = e.notification_id
ON CONFLICT (provider_code, provider_event_id) DO NOTHING;

REVOKE ALL ON notification_evidence_inbox FROM PUBLIC;
GRANT SELECT, INSERT ON notification_evidence_inbox TO agendamento_runtime;
GRANT UPDATE (notification_id) ON notification_evidence_inbox TO agendamento_runtime;
