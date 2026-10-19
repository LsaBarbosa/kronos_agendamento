DO $$
DECLARE source_event_unique text;
BEGIN
    SELECT conname
      INTO source_event_unique
      FROM pg_constraint
     WHERE conrelid = 'notification_intent'::regclass
       AND contype = 'u'
       AND pg_get_constraintdef(oid) = 'UNIQUE (source_event_id)';
    IF source_event_unique IS NULL THEN
        RAISE EXCEPTION 'Expected unique source_event_id constraint on notification_intent';
    END IF;
    EXECUTE format('ALTER TABLE notification_intent DROP CONSTRAINT %I', source_event_unique);
END $$;

ALTER TABLE notification_intent
    ADD COLUMN due_at TIMESTAMPTZ;

UPDATE notification_intent SET due_at = created_at;

ALTER TABLE notification_intent
    ALTER COLUMN due_at SET DEFAULT clock_timestamp(),
    ALTER COLUMN due_at SET NOT NULL;

ALTER TABLE notification_intent
    DROP CONSTRAINT ck_notification_type;

ALTER TABLE notification_intent
    ADD CONSTRAINT ck_notification_type CHECK
        (message_type IN ('APPOINTMENT_CONFIRMED', 'APPOINTMENT_RESCHEDULED',
                          'APPOINTMENT_CANCELLED', 'APPOINTMENT_REMINDER'));

CREATE INDEX ix_notification_reminder_due
    ON notification_intent(next_attempt_at, due_at, notification_id)
    WHERE message_type = 'APPOINTMENT_REMINDER' AND state IN ('PENDING', 'RETRY');

COMMENT ON COLUMN notification_intent.due_at IS
    'Agendamento UTC original; next_attempt_at pode mudar por retry.';
