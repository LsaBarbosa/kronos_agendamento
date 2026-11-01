\set ON_ERROR_STOP on

BEGIN;

WITH run AS (
    SELECT :'run_id'::text AS run_id
), fixtures AS (
    SELECT gen_random_uuid() AS tenant_id,
           format('agd054-%s-t%s', run.run_id, lpad(series.number::text, 2, '0')) AS slug,
           series.number
    FROM run
    CROSS JOIN generate_series(1, :tenant_count) AS series(number)
)
INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status)
SELECT tenant_id, 'Synthetic Load Tenant', slug, 'America/Sao_Paulo', 'ACTIVE'
FROM fixtures;

INSERT INTO agendamento.service (
    service_id, tenant_id, name, normalized_name, description,
    duration_minutes, buffer_after_minutes, status, version
)
SELECT gen_random_uuid(), tenant_id, 'Synthetic Load Service', 'synthetic-load-service',
       'Fixture exclusiva de carga', 15, 0, 'ACTIVE', 0
FROM agendamento.tenant
WHERE slug LIKE ('agd054-' || :'run_id' || '-t%');

INSERT INTO agendamento.weekly_schedule (tenant_id, version)
SELECT tenant_id, 1
FROM agendamento.tenant
WHERE slug LIKE ('agd054-' || :'run_id' || '-t%');

INSERT INTO agendamento.weekly_period (tenant_id, weekday, local_start, local_end)
SELECT t.tenant_id, weekday.name, TIME '09:00', TIME '17:00'
FROM agendamento.tenant t
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'),
                   ('FRIDAY'), ('SATURDAY'), ('SUNDAY')) AS weekday(name)
WHERE t.slug LIKE ('agd054-' || :'run_id' || '-t%');

INSERT INTO agendamento.availability_policy (
    tenant_id, slot_step_minutes, minimum_notice_hours, booking_horizon_days,
    cancellation_cutoff_hours, reminder_offset_hours, version
)
SELECT tenant_id, 15, 1, 30, 2, 2, 1
FROM agendamento.tenant
WHERE slug LIKE ('agd054-' || :'run_id' || '-t%');

UPDATE agendamento.tenant_publication publication
SET contact_phone = '+5511999990000', published = TRUE, version = 1,
    updated_at = clock_timestamp()
FROM agendamento.tenant tenant
WHERE tenant.tenant_id = publication.tenant_id
  AND tenant.slug LIKE ('agd054-' || :'run_id' || '-t%');

INSERT INTO agendamento.subscription (
    subscription_id, tenant_id, status, trial_started_at, trial_ends_at, version
)
SELECT gen_random_uuid(), tenant_id, 'TRIALING', now(), now() + INTERVAL '336 hours', 1
FROM agendamento.tenant
WHERE slug LIKE ('agd054-' || :'run_id' || '-t%');

COMMIT;
