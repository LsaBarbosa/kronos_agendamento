CREATE INDEX ix_dunning_attempt_metrics_recent
    ON billing_dunning_attempt (updated_at DESC, attempt_id DESC);

CREATE INDEX ix_outbox_reconciliation_metrics
    ON outbox_event (occurred_at, event_id)
    WHERE state = 'RECONCILIATION_REQUIRED';

CREATE INDEX ix_notification_unknown_metrics
    ON notification_intent (notification_id DESC)
    WHERE state = 'OUTCOME_UNKNOWN';
