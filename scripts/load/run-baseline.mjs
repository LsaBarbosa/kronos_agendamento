import { randomBytes, randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { spawnSync } from 'node:child_process';

const runId = requiredEnv('LOAD_RUN_ID', /^[a-f0-9]{10}$/);
const tenantCount = integerEnv('LOAD_TENANTS', 4, 2, 20);
const availabilityRequests = integerEnv('LOAD_AVAILABILITY_REQUESTS', 100, 1, 100000);
const createRequests = integerEnv('LOAD_CREATE_REQUESTS', 24, tenantCount, 10000);
const conflictRounds = integerEnv('LOAD_CONFLICT_ROUNDS', 4, 1, 1000);
const concurrency = integerEnv('LOAD_CONCURRENCY', 8, 1, 64);
const baseUrl = requiredEnv('LOAD_BASE_URL');
const timezone = 'America/Sao_Paulo';
const startedAt = new Date().toISOString();
const totalStart = performance.now();
let currentPhase = 'initialization';
let nextPhone = 100000000;

function requiredEnv(name, pattern) {
  const value = process.env[name];
  if (!value || (pattern && !pattern.test(value))) throw new Error(`invalid_${name.toLowerCase()}`);
  return value;
}

function integerEnv(name, fallback, minimum, maximum) {
  const raw = process.env[name];
  const value = raw === undefined ? fallback : Number(raw);
  if (!Number.isInteger(value) || value < minimum || value > maximum) throw new Error(`invalid_${name.toLowerCase()}`);
  return value;
}

function localDate(offsetDays) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date());
  const current = `${parts.find((part) => part.type === 'year').value}-${parts.find((part) => part.type === 'month').value}-${parts.find((part) => part.type === 'day').value}`;
  const date = new Date(`${current}T12:00:00Z`);
  date.setUTCDate(date.getUTCDate() + offsetDays);
  return date.toISOString().slice(0, 10);
}

function percentile(sorted, fraction) {
  if (sorted.length === 0) return null;
  return Number(sorted[Math.max(0, Math.ceil(fraction * sorted.length) - 1)].toFixed(2));
}

function summarize(samples, elapsedMs, expectedBusinessConflicts = 0) {
  const sorted = samples.map((sample) => sample.latencyMs).sort((a, b) => a - b);
  const success = samples.filter((sample) => sample.classification === 'success').length;
  const businessRejections = samples.filter((sample) => sample.classification === 'business_rejection').length;
  const technicalFailures = samples.filter((sample) => sample.classification === 'technical_failure').length;
  const expectedConflicts = samples.filter((sample) => sample.expectedConflict).length;
  const statusCounts = {};
  for (const sample of samples) {
    const key = sample.status === null ? 'transport_error' : String(sample.status);
    statusCounts[key] = (statusCounts[key] ?? 0) + 1;
  }
  return {
    requests: samples.length,
    success,
    businessRejections,
    expectedConflicts,
    unexpectedBusinessRejections: businessRejections - expectedConflicts,
    expectedBusinessConflicts,
    technicalFailures,
    statusCounts,
    durationMs: Number(elapsedMs.toFixed(2)),
    throughputPerSecond: samples.length >= 2 && elapsedMs > 0 ? Number((samples.length * 1000 / elapsedMs).toFixed(2)) : null,
    latencyMs: {
      p50: percentile(sorted, 0.50), p95: percentile(sorted, 0.95), p99: percentile(sorted, 0.99),
      min: sorted.length ? Number(sorted[0].toFixed(2)) : null,
      max: sorted.length ? Number(sorted.at(-1).toFixed(2)) : null,
    },
  };
}

async function request(path, options = {}) {
  const began = performance.now();
  try {
    const response = await fetch(`${baseUrl}${path}`, { redirect: 'manual', ...options });
    const text = await response.text();
    let parsed;
    if (text) {
      try { parsed = JSON.parse(text); } catch { parsed = undefined; }
    }
    return {
      status: response.status,
      headers: response.headers,
      body: parsed,
      latencyMs: Number((performance.now() - began).toFixed(2)),
      transportErrorClass: null,
    };
  } catch (error) {
    return {
      status: null,
      headers: new Headers(),
      body: undefined,
      latencyMs: Number((performance.now() - began).toFixed(2)),
      transportErrorClass: error?.constructor?.name ?? 'Error',
    };
  }
}

function classify(result, expectedStatuses = [201], expectedConflict = false) {
  if (result.status !== null && expectedStatuses.includes(result.status)) return 'success';
  if (result.status !== null && result.status >= 400 && result.status < 500) return 'business_rejection';
  return 'technical_failure';
}

async function runPool(items, limit, work) {
  const results = new Array(items.length);
  let next = 0;
  async function worker() {
    while (true) {
      const index = next++;
      if (index >= items.length) return;
      results[index] = await work(items[index], index);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, () => worker()));
  return results;
}

async function csrfContext() {
  const result = await request('/api/v1/csrf');
  if (result.status !== 200 || !result.body?.token || !result.body?.headerName) throw new Error('csrf_initialization_failed');
  const setCookies = result.headers.getSetCookie?.() ?? [];
  const cookie = setCookies.map((header) => header.split(';', 1)[0]).filter(Boolean).join('; ');
  return { token: result.body.token, headerName: result.body.headerName, cookie };
}

function syntheticBooking(tenantIndex, label, serviceId, startsAt) {
  const currentPhone = nextPhone++;
  return {
    tenantIndex,
    label,
    startsAt,
    key: `agd054-${runId}-${randomUUID()}`,
    capability: randomBytes(32).toString('base64url'),
    body: JSON.stringify({
      serviceId,
      startsAt,
      client: { name: `AGD054-${runId}-T${String(tenantIndex + 1).padStart(2, '0')}-${label}`, phone: `+5511${currentPhone}` },
    }),
  };
}

async function prepare(tenant, booking) {
  const csrf = await csrfContext();
  const headers = {
    'Content-Type': 'application/json',
    'Idempotency-Key': booking.key,
    'X-Booking-Recovery': booking.capability,
    [csrf.headerName]: csrf.token,
  };
  if (csrf.cookie) headers.Cookie = csrf.cookie;
  const result = await request(`/api/v1/public/${encodeURIComponent(tenant.slug)}/booking-intents/current`, {
    method: 'POST', headers, body: booking.body,
  });
  if (result.status !== 204) throw new Error('booking_intent_prepare_failed');
  booking.csrf = csrf;
}

async function postAppointment(tenant, booking, gate) {
  if (gate) await gate;
  const csrf = booking.csrf;
  const headers = {
    'Content-Type': 'application/json',
    'Idempotency-Key': booking.key,
    'X-Booking-Recovery': booking.capability,
    [csrf.headerName]: csrf.token,
  };
  if (csrf.cookie) headers.Cookie = csrf.cookie;
  const result = await request(`/api/v1/public/${encodeURIComponent(tenant.slug)}/appointments`, {
    method: 'POST', headers, body: booking.body,
  });
  return {
    ...result,
    classification: classify(result),
    replayed: result.headers.get('Idempotency-Replayed') === 'true',
    appointmentId: result.body?.appointmentId ?? null,
  };
}

async function databaseProbe(expectedAppointments, expectedIntents) {
  const project = requiredEnv('LOAD_COMPOSE_PROJECT', /^agd054-load-[a-f0-9]{10}$/);
  const envFile = requiredEnv('LOAD_ENV_FILE');
  const composeFile = requiredEnv('LOAD_COMPOSE_FILE');
  const repoRoot = requiredEnv('LOAD_REPO_ROOT');
  const slugPrefix = `agd054-${runId}-`;
  const clientPrefix = `AGD054-${runId}-`;
  const sql = `
WITH scoped AS (
  SELECT tenant_id, slug, right(slug, 2) AS ordinal
  FROM agendamento.tenant WHERE slug LIKE '${slugPrefix}t%'
), scoped_appointments AS (
  SELECT appointment.* FROM agendamento.appointment appointment
  JOIN scoped tenant USING (tenant_id)
), counts AS (
  SELECT
    (SELECT count(*) FROM scoped) AS tenant_count,
    (SELECT count(*) FROM scoped_appointments) AS appointment_count,
    (SELECT count(*) FROM agendamento.appointment_history history JOIN scoped_appointments appointment USING (tenant_id, appointment_id)) AS history_count,
    (SELECT count(*) FROM agendamento.audit_event audit JOIN scoped tenant USING (tenant_id) WHERE audit.action = 'APPOINTMENT_CONFIRMED') AS audit_count,
    (SELECT count(*) FROM agendamento.outbox_event event JOIN scoped tenant USING (tenant_id) WHERE event.event_type = 'AppointmentConfirmed') AS outbox_count,
    (SELECT count(*) FROM agendamento.idempotency_record idem JOIN scoped tenant USING (tenant_id) WHERE idem.command_scope = 'appointments.public.create.v1' AND idem.state = 'COMPLETED') AS idempotency_count,
    (SELECT count(*) FROM agendamento.public_booking_intent intent JOIN scoped tenant USING (tenant_id) WHERE intent.command_scope = 'appointments.public.create.v1') AS intent_count,
    (SELECT count(*) FROM scoped_appointments a JOIN scoped t USING (tenant_id) WHERE a.client_name_snapshot NOT LIKE '${clientPrefix}T' || t.ordinal || '-%') AS cross_tenant_names,
    (SELECT count(*) FROM scoped_appointments a WHERE NOT EXISTS (SELECT 1 FROM agendamento.appointment_history h WHERE h.tenant_id = a.tenant_id AND h.appointment_id = a.appointment_id AND h.to_status = 'CONFIRMED')) AS missing_history,
    (SELECT count(*) FROM scoped_appointments a WHERE NOT EXISTS (SELECT 1 FROM agendamento.audit_event e WHERE e.tenant_id = a.tenant_id AND e.target_type = 'APPOINTMENT' AND e.target_id = a.appointment_id AND e.action = 'APPOINTMENT_CONFIRMED')) AS missing_audit,
    (SELECT count(*) FROM scoped_appointments a WHERE NOT EXISTS (SELECT 1 FROM agendamento.outbox_event e WHERE e.tenant_id = a.tenant_id AND e.aggregate_type = 'APPOINTMENT' AND e.aggregate_id = a.appointment_id AND e.event_type = 'AppointmentConfirmed')) AS missing_outbox,
    (SELECT count(*) FROM scoped_appointments a WHERE NOT EXISTS (SELECT 1 FROM agendamento.idempotency_record i WHERE i.tenant_id = a.tenant_id AND i.command_scope = 'appointments.public.create.v1' AND i.state = 'COMPLETED' AND i.result->>'appointmentId' = a.appointment_id::text)) AS missing_idempotency_receipt,
    (SELECT count(*) FROM scoped_appointments a WHERE NOT EXISTS (SELECT 1 FROM agendamento.client c WHERE c.tenant_id = a.tenant_id AND c.client_id = a.client_id)) AS invalid_client_tenant_reference,
    (SELECT count(*) FROM scoped_appointments a WHERE NOT EXISTS (SELECT 1 FROM agendamento.service s WHERE s.tenant_id = a.tenant_id AND s.service_id = a.service_id)) AS invalid_service_tenant_reference,
    (SELECT count(*) FROM scoped_appointments a JOIN scoped_appointments b ON a.tenant_id = b.tenant_id AND a.appointment_id < b.appointment_id AND a.status = 'CONFIRMED' AND b.status = 'CONFIRMED' AND tstzrange(a.starts_at, a.occupied_until, '[)') && tstzrange(b.starts_at, b.occupied_until, '[)')) AS overlap_pairs
)
SELECT json_build_object(
  'tenantCount', tenant_count,
  'appointmentCount', appointment_count,
  'historyCount', history_count,
  'auditCount', audit_count,
  'outboxCount', outbox_count,
  'idempotencyCount', idempotency_count,
  'intentCount', intent_count,
  'crossTenantNames', cross_tenant_names,
  'missingHistory', missing_history,
  'missingAudit', missing_audit,
  'missingOutbox', missing_outbox,
  'missingIdempotencyReceipt', missing_idempotency_receipt,
  'invalidClientTenantReference', invalid_client_tenant_reference,
  'invalidServiceTenantReference', invalid_service_tenant_reference,
  'overlapPairs', overlap_pairs,
  'appointmentsByTenant', (SELECT json_agg(json_build_object('ordinal', right(t.slug, 2), 'appointments', (SELECT count(*) FROM scoped_appointments a WHERE a.tenant_id = t.tenant_id)) ORDER BY t.slug) FROM scoped t)
) FROM counts;
`;
  const args = [
    'compose', '-p', project, '--env-file', envFile, '-f', composeFile, '--project-directory', repoRoot,
    'exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'agd054_owner', '-d', 'agd054_load', '-tA', '-c', sql,
  ];
  const probe = spawnSync('docker', args, { encoding: 'utf8', timeout: 30000 });
  if (probe.status !== 0) throw new Error('postgres_invariant_query_failed');
  let result;
  try {
    const jsonLine = probe.stdout.trim().split('\n').findLast((line) => line.startsWith('{'));
    if (!jsonLine) throw new Error('missing_json_line');
    result = JSON.parse(jsonLine);
  } catch {
    throw new Error('postgres_invariant_result_invalid');
  }
  const expected = {
    tenantCount,
    appointmentCount: expectedAppointments,
    historyCount: expectedAppointments,
    auditCount: expectedAppointments,
    outboxCount: expectedAppointments,
    idempotencyCount: expectedAppointments,
    intentCount: expectedIntents,
    crossTenantNames: 0,
    missingHistory: 0,
    missingAudit: 0,
    missingOutbox: 0,
    missingIdempotencyReceipt: 0,
    invalidClientTenantReference: 0,
    invalidServiceTenantReference: 0,
    overlapPairs: 0,
  };
  for (const [field, value] of Object.entries(expected)) {
    if (Number(result[field]) !== value) throw new Error(`postgres_invariant_failed_${field}`);
  }
  if (!Array.isArray(result.appointmentsByTenant) || result.appointmentsByTenant.length !== tenantCount || result.appointmentsByTenant.some((row) => Number(row.appointments) < 1)) {
    throw new Error('tenant_distribution_invariant_failed');
  }
  return { expected, observed: result };
}

async function main() {
  currentPhase = 'fixture_discovery';
  const discoveryDate = localDate(2);
  const tenants = [];
  for (let index = 0; index < tenantCount; index += 1) {
    const slug = `agd054-${runId}-t${String(index + 1).padStart(2, '0')}`;
    const services = await request(`/api/v1/public/${slug}/services`);
    if (services.status !== 200 || !Array.isArray(services.body) || !services.body[0]?.serviceId) throw new Error('synthetic_service_discovery_failed');
    const serviceId = services.body[0].serviceId;
    const availability = await request(`/api/v1/public/${slug}/availability?serviceId=${encodeURIComponent(serviceId)}&from=${discoveryDate}&to=${discoveryDate}`);
    if (availability.status !== 200 || !Array.isArray(availability.body?.days?.[0]?.slots)) throw new Error('synthetic_slot_discovery_failed');
    tenants.push({ index, slug, serviceId, slots: availability.body.days[0].slots.map((slot) => slot.startsAt) });
  }

  const perTenant = Math.ceil(createRequests / tenantCount);
  const neededPerTenant = tenants.map((tenant) => perTenant + (tenant.index === 0 ? conflictRounds : 0) + (tenant.index === tenantCount - 1 ? 1 : 0));
  if (tenants.some((tenant) => tenant.slots.length < neededPerTenant[tenant.index])) throw new Error('fixture_schedule_has_insufficient_slots');

  const availabilitySamples = [];
  currentPhase = 'availability_load';
  const availabilityStart = performance.now();
  const availabilityInput = Array.from({ length: availabilityRequests }, (_, index) => tenants[index % tenantCount]);
  const availabilityResults = await runPool(availabilityInput, concurrency, async (tenant) => {
    const result = await request(`/api/v1/public/${tenant.slug}/availability?serviceId=${encodeURIComponent(tenant.serviceId)}&from=${discoveryDate}&to=${discoveryDate}`);
    return {
      status: result.status,
      latencyMs: result.latencyMs,
      classification: result.status === 200 ? 'success' : result.status >= 400 && result.status < 500 ? 'business_rejection' : 'technical_failure',
      expectedConflict: false,
    };
  });
  availabilitySamples.push(...availabilityResults);
  const availabilityDuration = performance.now() - availabilityStart;

  const createJobs = Array.from({ length: createRequests }, (_, index) => {
    const tenantIndex = index % tenantCount;
    const tenant = tenants[tenantIndex];
    const ordinalForTenant = Math.floor(index / tenantCount);
    const booking = syntheticBooking(tenantIndex, `CREATE-${String(index + 1).padStart(5, '0')}`, tenant.serviceId, tenant.slots[ordinalForTenant]);
    return { tenant, booking };
  });
  const prepareStart = performance.now();
  currentPhase = 'distinct_creation_prepare';
  await runPool(createJobs, concurrency, ({ tenant, booking }) => prepare(tenant, booking));
  const prepareDuration = performance.now() - prepareStart;
  const createStart = performance.now();
  currentPhase = 'distinct_creation';
  const createSamples = await runPool(createJobs, concurrency, async ({ tenant, booking }) => {
    const result = await postAppointment(tenant, booking);
    return { ...result, classification: result.status === 201 ? 'success' : result.status >= 400 && result.status < 500 ? 'business_rejection' : 'technical_failure', tenantOrdinal: String(tenantIndexOf(tenant) + 1).padStart(2, '0') };
  });
  const createDuration = performance.now() - createStart;
  if (createSamples.some((sample) => sample.status !== 201)) throw new Error('distinct_slot_creation_failed');

  const retryTenant = tenants.at(-1);
  currentPhase = 'same_intent_retry';
  const retryBooking = syntheticBooking(retryTenant.index, 'RETRY-SAME-INTENT', retryTenant.serviceId, retryTenant.slots[perTenant]);
  await prepare(retryTenant, retryBooking);
  const firstRetryScenario = await postAppointment(retryTenant, retryBooking);
  if (firstRetryScenario.status !== 201 || firstRetryScenario.replayed || !firstRetryScenario.appointmentId) throw new Error('initial_idempotent_intent_failed');
  const retryStart = performance.now();
  const retryResponse = await postAppointment(retryTenant, retryBooking);
  const retryDuration = performance.now() - retryStart;
  if (retryResponse.status !== 201 || !retryResponse.replayed || retryResponse.appointmentId !== firstRetryScenario.appointmentId) throw new Error('same_intent_retry_invariant_failed');
  const retrySamples = [{ ...retryResponse, classification: 'success', tenantOrdinal: String(retryTenant.index + 1).padStart(2, '0') }];

  const conflictSamples = [];
  currentPhase = 'same_slot_contention';
  const conflictStart = performance.now();
  for (let round = 0; round < conflictRounds; round += 1) {
    const tenant = tenants[0];
    const slot = tenant.slots[perTenant + round];
    const contenders = [0, 1].map((contender) => ({
      tenant,
      booking: syntheticBooking(tenant.index, `CONFLICT-${String(round + 1).padStart(4, '0')}-C${contender + 1}`, tenant.serviceId, slot),
    }));
    await runPool(contenders, 2, ({ tenant: contenderTenant, booking }) => prepare(contenderTenant, booking));
    let release;
    const gate = new Promise((resolve) => { release = resolve; });
    const pair = contenders.map(({ tenant: contenderTenant, booking }) => postAppointment(contenderTenant, booking, gate));
    release();
    const responses = await Promise.all(pair);
    const winners = responses.filter((result) => result.status === 201).length;
    const expectedLosers = responses.filter((result) => result.status === 409 && result.body?.error?.code === 'SLOT_NO_LONGER_AVAILABLE').length;
    if (winners !== 1 || expectedLosers !== 1) throw new Error('same_slot_race_invariant_failed');
    conflictSamples.push(...responses.map((result) => ({
      ...result,
      classification: result.status === 201 ? 'success' : 'business_rejection',
      expectedConflict: result.status === 409 && result.body?.error?.code === 'SLOT_NO_LONGER_AVAILABLE',
      tenantOrdinal: '01',
    })));
  }
  const conflictDuration = performance.now() - conflictStart;

  const expectedAppointments = createRequests + conflictRounds + 1;
  const expectedIntents = createRequests + conflictRounds * 2 + 1;
  currentPhase = 'postgres_invariants';
  const postgres = await databaseProbe(expectedAppointments, expectedIntents);
  const totalDuration = performance.now() - totalStart;
  const result = {
    schema: 'agd054-load-baseline/v1',
    runId,
    startedAt,
    finishedAt: new Date().toISOString(),
    timezone,
    configuration: { tenantCount, availabilityRequests, createRequests, conflictRounds, concurrency, createBookingDate: discoveryDate },
    system: {
      node: process.version,
      platform: process.platform,
      architecture: process.arch,
      cpuCount: Number(process.env.LOAD_HOST_CPU_COUNT ?? 0) || null,
      cpuModel: process.env.LOAD_HOST_CPU_MODEL ?? 'not-recorded',
      memoryTotalBytes: Number(process.env.LOAD_HOST_MEMORY_BYTES ?? 0) || null,
      kernel: process.env.LOAD_HOST_KERNEL ?? 'not-recorded',
      dockerEngine: process.env.LOAD_DOCKER_ENGINE_VERSION ?? 'not-recorded',
      dockerCompose: process.env.LOAD_DOCKER_COMPOSE_VERSION ?? 'not-recorded',
      postgresImage: process.env.LOAD_POSTGRES_IMAGE ?? 'not-recorded',
      appImageId: process.env.LOAD_APP_IMAGE_ID ?? 'not-recorded',
      postgresVersion: process.env.LOAD_POSTGRES_VERSION ?? 'not-recorded',
      schemaVersion: process.env.LOAD_SCHEMA_VERSION ?? 'not-recorded',
      applicationCommit: process.env.LOAD_APPLICATION_COMMIT ?? 'not-recorded',
      javaVersion: process.env.LOAD_JAVA_VERSION ?? 'not-recorded',
    },
    phases: {
      availability: summarize(availabilitySamples, availabilityDuration),
      distinctSlotCreation: summarize(createSamples, createDuration),
      sameIntentRetry: summarize(retrySamples, retryDuration),
      sameSlotContention: summarize(conflictSamples, conflictDuration, conflictRounds),
      prebindSetup: { durationMs: Number(prepareDuration.toFixed(2)), requests: createJobs.length },
      totalDurationMs: Number(totalDuration.toFixed(2)),
    },
    idempotencyReplayEvidence: {
      originalCreationAccepted: true,
      retryReturnedCreatedStatus: retryResponse.status === 201,
      retryMarkedAsReplay: retryResponse.replayed,
      retryReturnedSameAppointmentReference: retryResponse.appointmentId === firstRetryScenario.appointmentId,
    },
    tenantCreateDistribution: Object.fromEntries(tenants.map((tenant) => {
      const samples = createSamples.filter((sample) => sample.tenantOrdinal === String(tenant.index + 1).padStart(2, '0'));
      return [`tenant_${String(tenant.index + 1).padStart(2, '0')}`, summarize(samples, createDuration)];
    })),
    postRunPostgresInvariants: postgres,
    classification: 'synthetic single-host baseline; not an SLO or production capacity claim',
  };
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  if ([...availabilitySamples, ...createSamples, ...retrySamples, ...conflictSamples].some((sample) => sample.classification === 'technical_failure' || (sample.classification === 'business_rejection' && !sample.expectedConflict))) process.exitCode = 1;
}

function tenantIndexOf(tenant) { return tenant.index; }

main().catch((error) => {
  process.stderr.write(`load_baseline_failed phase=${currentPhase} code=${error?.message?.match(/^[A-Za-z0-9_]+$/)?.[0] ?? error?.constructor?.name ?? 'Error'}\n`);
  process.exitCode = 1;
});
