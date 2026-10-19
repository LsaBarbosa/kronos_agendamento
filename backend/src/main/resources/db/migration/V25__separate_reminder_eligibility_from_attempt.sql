ALTER TABLE notification_intent
    ADD COLUMN reminder_eligible BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE notification_intent
   SET reminder_eligible = FALSE
 WHERE message_type = 'APPOINTMENT_REMINDER'
   AND state = 'OBSOLETE';

COMMENT ON COLUMN notification_intent.reminder_eligible IS
    'Elegibilidade para novos envios de lembrete, separada do resultado da tentativa em curso.';
