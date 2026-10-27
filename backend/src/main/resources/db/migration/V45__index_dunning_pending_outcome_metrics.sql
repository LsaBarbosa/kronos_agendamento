CREATE INDEX ix_dunning_attempt_metrics_pending_outcome
    ON billing_dunning_attempt (updated_at DESC, attempt_id DESC)
    WHERE state IN ('OUTCOME_UNKNOWN', 'RECONCILING');
