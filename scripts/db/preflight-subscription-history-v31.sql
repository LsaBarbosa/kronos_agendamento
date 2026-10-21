-- Read-only V30 -> V31 preflight. Empty result means all existing rows satisfy
-- the complete transition allowlist; do not rewrite or delete incompatible rows.
SELECT event_id,
       tenant_id,
       subscription_id,
       event_type,
       from_status,
       to_status,
       version
  FROM agendamento.subscription_history
 WHERE NOT (
     (event_type = 'TRIAL_GRANTED'
         AND from_status IS NULL
         AND to_status = 'TRIALING'
         AND version = 1)
     OR
     (event_type = 'TRIAL_EXPIRED'
         AND from_status = 'TRIALING'
         AND to_status = 'SUSPENDED'
         AND version = 2)
 ) IS TRUE
 ORDER BY event_at, event_id;
