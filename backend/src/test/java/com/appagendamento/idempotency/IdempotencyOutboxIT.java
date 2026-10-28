package com.appagendamento.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.idempotency.application.IdempotencyKey;
import com.appagendamento.idempotency.application.IdempotencyKeyReusedException;
import com.appagendamento.idempotency.application.OperationInProgressException;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.outbox.api.DeliveryResult;
import com.appagendamento.outbox.api.OutboxClaim;
import com.appagendamento.outbox.api.OutboxStore;
import com.appagendamento.outbox.application.IdempotentEventConsumer;
import com.appagendamento.outbox.application.OutboxWorker;
import com.appagendamento.support.PostgresTestEnvironment;
import com.appagendamento.tenant.application.CreateTenant;
import com.appagendamento.tenant.application.RenameTenant;
import com.appagendamento.tenant.domain.Tenant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class IdempotencyOutboxIT {

  private static final String ISSUER = "https://identity.test/realms/agendamento";
  private JdbcTemplate migrationJdbc;
  @Autowired private JdbcTemplate runtimeJdbc;
  @Autowired private CreateTenant createTenant;
  @Autowired private RenameTenant renameTenant;
  @Autowired private IdempotencyCoordinator idempotency;
  @Autowired private OutboxStore outbox;
  @Autowired private IdempotentEventConsumer consumer;
  @Autowired private OutboxWorker worker;
  @Autowired private PlatformTransactionManager transactionManager;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void privilegedFixtures() {
    migrationJdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
  }

  @Test
  void replaysCommittedRenameWithoutRepeatingMutationAuditOrOutbox() {
    Tenant tenant = tenant();
    AuthenticatedAdmin admin = admin(tenant.tenantId());
    String key = "rename-" + UUID.randomUUID();
    long eligibleBefore = outbox.metricsSnapshot().orElseThrow().eligibleCount();

    var first = renameTenant.execute(admin, tenant.tenantId(), key, "Renomeada", 0);
    var replay = renameTenant.execute(admin, tenant.tenantId(), key, " Renomeada ", 0);

    assertThat(first.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.operationId()).isEqualTo(first.operationId());
    assertThat(replay.value()).isEqualTo(first.value());
    assertThat(replay.value().version()).isEqualTo(1);
    var outboxMetrics = outbox.metricsSnapshot().orElseThrow();
    assertThat(outboxMetrics.eligibleCount()).isEqualTo(eligibleBefore + 1);
    assertThat(outboxMetrics.oldestEligibleAgeSeconds()).isGreaterThanOrEqualTo(0);
    assertThat(outboxMetrics.capped()).isFalse();
    assertThat(count("audit_event", "tenant_id", tenant.tenantId(), "TENANT_RENAMED")).isEqualTo(1);
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.outbox_event WHERE operation_id = ?",
                Integer.class,
                first.operationId()))
        .isEqualTo(1);
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE operation_id = ? AND state = 'COMPLETED'",
                Integer.class,
                first.operationId()))
        .isEqualTo(1);
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT correlation_id FROM agendamento.outbox_event WHERE operation_id = ?",
                UUID.class,
                first.operationId()))
        .isNotEqualTo(first.operationId());
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT key_digest FROM agendamento.idempotency_record WHERE operation_id = ?",
                String.class,
                first.operationId()))
        .isEqualTo(IdempotencyKey.digest(key));
  }

  @Test
  void reusedKeyWithChangedCommandIsRejectedAndTenantAndScopeAreIsolated() {
    Tenant firstTenant = tenant();
    Tenant secondTenant = tenant();
    String sharedKey = "shared-" + UUID.randomUUID();
    AuthenticatedAdmin firstAdmin = admin(firstTenant.tenantId());
    AuthenticatedAdmin secondAdmin = admin(secondTenant.tenantId());
    var first = renameTenant.execute(firstAdmin, firstTenant.tenantId(), sharedKey, "Alpha", 0);

    assertThatThrownBy(
            () -> renameTenant.execute(firstAdmin, firstTenant.tenantId(), sharedKey, "Beta", 0))
        .isExactlyInstanceOf(IdempotencyKeyReusedException.class);
    var otherTenant =
        renameTenant.execute(secondAdmin, secondTenant.tenantId(), sharedKey, "Beta", 0);
    assertThat(otherTenant.operationId()).isNotEqualTo(first.operationId());

    CommandResultCodec<String> codec = stringCodec();
    String digest = "a".repeat(64);
    var firstScope =
        idempotency.execute(
            firstTenant.tenantId(),
            "test.scope.one",
            sharedKey,
            digest,
            () -> {},
            operationId -> "one",
            codec);
    var secondScope =
        idempotency.execute(
            firstTenant.tenantId(),
            "test.scope.two",
            sharedKey,
            digest,
            () -> {},
            operationId -> "two",
            codec);
    assertThat(firstScope.operationId()).isNotEqualTo(secondScope.operationId());
  }

  @Test
  void concurrentSameKeyRunsOnlyOneLogicalCommandAcrossIndependentTransactions() throws Exception {
    UUID tenantId = tenant().tenantId();
    String key = "concurrent-" + UUID.randomUUID();
    String digest = "b".repeat(64);
    CountDownLatch firstCommandEntered = new CountDownLatch(1);
    CountDownLatch releaseFirstCommand = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    AtomicInteger executions = new AtomicInteger();
    try (ExecutorService calls = Executors.newFixedThreadPool(2)) {
      Future<IdempotentCommandResult<String>> first =
          calls.submit(
              () ->
                  idempotency.execute(
                      tenantId,
                      "test.concurrent.v1",
                      key,
                      digest,
                      () -> {},
                      operationId -> {
                        executions.incrementAndGet();
                        firstCommandEntered.countDown();
                        await(releaseFirstCommand);
                        return "committed";
                      },
                      stringCodec()));
      assertThat(firstCommandEntered.await(10, TimeUnit.SECONDS)).isTrue();
      Future<IdempotentCommandResult<String>> second =
          calls.submit(
              () -> {
                secondStarted.countDown();
                return idempotency.execute(
                    tenantId,
                    "test.concurrent.v1",
                    key,
                    digest,
                    () -> {},
                    operationId -> {
                      executions.incrementAndGet();
                      return "duplicate";
                    },
                    stringCodec());
              });
      assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
      try {
        assertThat(awaitUniqueKeyWait()).isTrue();
      } finally {
        releaseFirstCommand.countDown();
      }
      var firstResult = first.get(20, TimeUnit.SECONDS);
      var secondResult = second.get(20, TimeUnit.SECONDS);

      assertThat(executions).hasValue(1);
      assertThat(firstResult.operationId()).isEqualTo(secondResult.operationId());
      assertThat(List.of(firstResult.replayed(), secondResult.replayed()))
          .containsExactlyInAnyOrder(false, true);
      assertThat(firstResult.value()).isEqualTo("committed");
      assertThat(secondResult.value()).isEqualTo("committed");
    }
  }

  @Test
  void durableInProgressRecordReturnsOperationIdWithoutStartingAnotherCommand() {
    UUID tenantId = UUID.randomUUID();
    String key = "in-progress-" + UUID.randomUUID();
    String scope = "test.in-progress.v1";
    String digest = "c".repeat(64);
    UUID operationId = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.idempotency_record "
            + "(tenant_id, command_scope, key_digest, request_digest, operation_id, state) "
            + "VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS')",
        tenantId,
        scope,
        IdempotencyKey.digest(key),
        digest,
        operationId);

    assertThatThrownBy(
            () ->
                idempotency.execute(
                    tenantId,
                    scope,
                    key,
                    digest,
                    () -> {},
                    ignored -> "must not execute",
                    stringCodec()))
        .isExactlyInstanceOf(OperationInProgressException.class)
        .hasFieldOrPropertyWithValue("operationId", operationId);
  }

  @Test
  void outerRollbackRemovesRenameAuditIdempotencyAndOutboxTogether() {
    Tenant tenant = tenant();
    AuthenticatedAdmin admin = admin(tenant.tenantId());
    String key = "rollback-" + UUID.randomUUID();
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      renameTenant.execute(admin, tenant.tenantId(), key, "Never committed", 0);
                      throw new IllegalStateException("simulate response-path rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(tenantName(tenant.tenantId())).isEqualTo(tenant.name());
    assertThat(tenantVersion(tenant.tenantId())).isZero();
    assertThat(count("audit_event", "tenant_id", tenant.tenantId(), "TENANT_RENAMED")).isZero();
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND key_digest = ?",
                Integer.class,
                tenant.tenantId(),
                IdempotencyKey.digest(key)))
        .isZero();
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.outbox_event WHERE tenant_id = ? AND event_type = 'TENANT_RENAMED'",
                Integer.class,
                tenant.tenantId()))
        .isZero();
  }

  @Test
  void lostResponseAfterCommitReplaysCommittedResult() {
    Tenant tenant = tenant();
    AuthenticatedAdmin admin = admin(tenant.tenantId());
    String key = "lost-response-" + UUID.randomUUID();
    var committed = renameTenant.execute(admin, tenant.tenantId(), key, "Response perdida", 0);
    UUID operationId = committed.operationId();
    committed = null;

    var replay = renameTenant.execute(admin, tenant.tenantId(), key, "Response perdida", 0);

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.operationId()).isEqualTo(operationId);
    assertThat(replay.value().version()).isEqualTo(1);
    assertThat(count("audit_event", "tenant_id", tenant.tenantId(), "TENANT_RENAMED")).isEqualTo(1);
  }

  @Test
  void revokedAdministratorCannotReplayPersistedResult() {
    Tenant tenant = tenant();
    AuthenticatedAdmin admin = admin(tenant.tenantId());
    String key = "revoked-replay-" + UUID.randomUUID();
    renameTenant.execute(admin, tenant.tenantId(), key, "Authorized once", 0);
    migrationJdbc.update(
        "UPDATE agendamento.admin_tenant_membership SET status = 'DISABLED' WHERE user_id = ?",
        admin.adminUserId());

    assertThatThrownBy(
            () -> renameTenant.execute(admin, tenant.tenantId(), key, "Authorized once", 0))
        .isExactlyInstanceOf(com.appagendamento.tenant.domain.TenantNotFoundException.class);
    assertThat(tenantVersion(tenant.tenantId())).isEqualTo(1);
    assertThat(count("audit_event", "tenant_id", tenant.tenantId(), "TENANT_RENAMED")).isEqualTo(1);
  }

  @Test
  void auditOrOutboxFailureRollsBackEveryPartOfTheLogicalCommand() {
    Tenant auditTenant = tenant();
    AuthenticatedAdmin auditAdmin = admin(auditTenant.tenantId());
    String auditKey = "audit-failure-" + UUID.randomUUID();
    installRejectTrigger(
        "audit_event",
        "reject_agd005_audit",
        "reject_agd005_audit_fn",
        "NEW.action = 'TENANT_RENAMED'");
    try {
      assertThatThrownBy(
              () ->
                  renameTenant.execute(auditAdmin, auditTenant.tenantId(), auditKey, "No audit", 0))
          .isInstanceOf(RuntimeException.class);
      assertAtomicRenameAbsent(auditTenant.tenantId(), auditKey);
    } finally {
      removeTrigger("audit_event", "reject_agd005_audit", "reject_agd005_audit_fn");
    }

    Tenant outboxTenant = tenant();
    AuthenticatedAdmin outboxAdmin = admin(outboxTenant.tenantId());
    String outboxKey = "outbox-failure-" + UUID.randomUUID();
    installRejectTrigger("outbox_event", "reject_agd005_outbox", "reject_agd005_outbox_fn", "TRUE");
    try {
      assertThatThrownBy(
              () ->
                  renameTenant.execute(
                      outboxAdmin, outboxTenant.tenantId(), outboxKey, "No outbox", 0))
          .isInstanceOf(RuntimeException.class);
      assertAtomicRenameAbsent(outboxTenant.tenantId(), outboxKey);
    } finally {
      removeTrigger("outbox_event", "reject_agd005_outbox", "reject_agd005_outbox_fn");
    }
  }

  @Test
  void expiredLeaseCanBeReclaimedAndStaleWorkerCannotConfirm() {
    Tenant tenant = tenant();
    var operation =
        renameTenant.execute(
            admin(tenant.tenantId()), tenant.tenantId(), "lease-" + UUID.randomUUID(), "Leased", 0);
    UUID eventId = eventFor(operation.operationId());
    postponeOtherPendingEvents(eventId);

    OutboxClaim firstClaim = outbox.claim(1, Duration.ofMinutes(1)).getFirst();
    assertThat(firstClaim.eventId()).isEqualTo(eventId);
    migrationJdbc.update(
        "UPDATE agendamento.outbox_event SET lease_until = clock_timestamp() - interval '1 second' WHERE event_id = ?",
        eventId);
    OutboxClaim reclaimed = outbox.claim(1, Duration.ofMinutes(1)).getFirst();

    assertThat(reclaimed.eventId()).isEqualTo(eventId);
    assertThat(reclaimed.claimToken()).isNotEqualTo(firstClaim.claimToken());
    assertThat(reclaimed.attemptCount()).isEqualTo(firstClaim.attemptCount() + 1);
    assertThat(outbox.markOutcomeUnknown(firstClaim, "DESTINATION_OUTCOME_UNKNOWN")).isFalse();
    assertThat(outboxState(eventId)).isEqualTo("CLAIMED");
    assertThat(outbox.markDelivered(firstClaim)).isFalse();
    assertThat(outbox.markDelivered(reclaimed)).isTrue();
    assertThat(outbox.markDelivered(reclaimed)).isFalse();
  }

  @Test
  void unknownOutcomeAfterLeaseExpiryIsPreservedWithoutAutomaticRedelivery() {
    Tenant tenant = tenant();
    var operation =
        renameTenant.execute(
            admin(tenant.tenantId()),
            tenant.tenantId(),
            "late-unknown-" + UUID.randomUUID(),
            "Unknown",
            0);
    UUID eventId = eventFor(operation.operationId());
    postponeOtherPendingEvents(eventId);
    OutboxWorker directWorker = new OutboxWorker(outbox, null, 1, 60, 8);

    assertThat(
            directWorker.processBatch(
                claim -> {
                  assertThat(claim.eventId()).isEqualTo(eventId);
                  migrationJdbc.update(
                      "UPDATE agendamento.outbox_event SET lease_until = clock_timestamp() - interval '1 second' WHERE event_id = ?",
                      eventId);
                  assertThat(outbox.markDelivered(claim)).isFalse();
                  return DeliveryResult.OUTCOME_UNKNOWN;
                }))
        .isEqualTo(1);

    assertThat(outboxState(eventId)).isEqualTo("RECONCILIATION_REQUIRED");
    assertThat(outbox.claim(1, Duration.ofMinutes(1))).isEmpty();
    assertThat(outbox.reprocess(eventId)).isFalse();
  }

  @Test
  void workerClaimsOnlyTheEventItIsAboutToPublish() {
    Tenant firstTenant = tenant();
    Tenant secondTenant = tenant();
    var firstOperation =
        renameTenant.execute(
            admin(firstTenant.tenantId()),
            firstTenant.tenantId(),
            "just-in-time-a-" + UUID.randomUUID(),
            "First",
            0);
    var secondOperation =
        renameTenant.execute(
            admin(secondTenant.tenantId()),
            secondTenant.tenantId(),
            "just-in-time-b-" + UUID.randomUUID(),
            "Second",
            0);
    UUID firstEvent = eventFor(firstOperation.operationId());
    UUID secondEvent = eventFor(secondOperation.operationId());
    postponeOtherPendingEvents(firstEvent, secondEvent);
    OutboxWorker directWorker = new OutboxWorker(outbox, null, 2, 60, 8);

    assertThat(
            directWorker.processBatch(
                claim -> {
                  assertThat(
                          runtimeJdbc.queryForObject(
                              "SELECT count(*) FROM agendamento.outbox_event WHERE event_id IN (?, ?) AND state = 'CLAIMED'",
                              Integer.class,
                              firstEvent,
                              secondEvent))
                      .isEqualTo(1);
                  return DeliveryResult.DELIVERED;
                }))
        .isEqualTo(2);

    assertThat(outboxState(firstEvent)).isEqualTo("DELIVERED");
    assertThat(outboxState(secondEvent)).isEqualTo("DELIVERED");
  }

  @Test
  void retriesReachDeadLetterAndUnknownOutcomesRequireControlledReprocessing() {
    Tenant tenant = tenant();
    var operation =
        renameTenant.execute(
            admin(tenant.tenantId()), tenant.tenantId(), "retry-" + UUID.randomUUID(), "Retry", 0);
    UUID eventId = eventFor(operation.operationId());
    postponeOtherPendingEvents(eventId);
    OutboxClaim claim = outbox.claim(1, Duration.ofMinutes(1)).getFirst();
    assertThat(outbox.markRetry(claim, Duration.ofMinutes(2), 8, "DESTINATION_RETRYABLE")).isTrue();
    assertThat(outboxState(eventId)).isEqualTo("RETRY");
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT next_attempt_at > clock_timestamp() + interval '1 minute' "
                    + "FROM agendamento.outbox_event WHERE event_id = ?",
                Boolean.class,
                eventId))
        .isTrue();
    assertThat(outbox.claim(1, Duration.ofMinutes(1))).isEmpty();
    migrationJdbc.update(
        "UPDATE agendamento.outbox_event SET next_attempt_at = clock_timestamp() WHERE event_id = ?",
        eventId);
    OutboxClaim secondAttempt = outbox.claim(1, Duration.ofMinutes(1)).getFirst();
    assertThat(secondAttempt.attemptCount()).isEqualTo(2);
    assertThat(outbox.markRetry(secondAttempt, Duration.ZERO, 2, "DESTINATION_RETRYABLE")).isTrue();
    assertThat(outboxState(eventId)).isEqualTo("DEAD_LETTER");
    assertThat(outbox.reprocess(eventId)).isTrue();
    assertThat(outbox.reprocess(eventId)).isFalse();
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT attempt_count = 0 AND reprocess_count = 1 FROM agendamento.outbox_event WHERE event_id = ?",
                Boolean.class,
                eventId))
        .isTrue();
    migrationJdbc.update(
        "UPDATE agendamento.outbox_event SET next_attempt_at = clock_timestamp() WHERE event_id = ?",
        eventId);
    OutboxClaim retryClaim = outbox.claim(1, Duration.ofMinutes(1)).getFirst();
    assertThat(retryClaim.eventId()).isEqualTo(eventId);
    assertThat(retryClaim.operationId()).isEqualTo(operation.operationId());
    assertThat(outbox.markOutcomeUnknown(retryClaim, "DESTINATION_OUTCOME_UNKNOWN")).isTrue();
    assertThat(outboxState(eventId)).isEqualTo("RECONCILIATION_REQUIRED");
    assertThat(outbox.reprocess(eventId)).isFalse();
    assertThat(outboxState(eventId)).isEqualTo("RECONCILIATION_REQUIRED");
    assertThat(eventFor(operation.operationId())).isEqualTo(eventId);
  }

  @Test
  void concurrentWorkersClaimDifferentRowsAndPublishOutsideDatabaseTransaction() throws Exception {
    Tenant firstTenant = tenant();
    Tenant secondTenant = tenant();
    var firstOperation =
        renameTenant.execute(
            admin(firstTenant.tenantId()),
            firstTenant.tenantId(),
            "worker-a-" + UUID.randomUUID(),
            "Worker A",
            0);
    var secondOperation =
        renameTenant.execute(
            admin(secondTenant.tenantId()),
            secondTenant.tenantId(),
            "worker-b-" + UUID.randomUUID(),
            "Worker B",
            0);
    UUID firstEvent = eventFor(firstOperation.operationId());
    UUID secondEvent = eventFor(secondOperation.operationId());
    postponeOtherPendingEvents(firstEvent, secondEvent);
    CyclicBarrier start = new CyclicBarrier(2);
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<List<OutboxClaim>> first = workers.submit(() -> claimAfter(start));
      Future<List<OutboxClaim>> second = workers.submit(() -> claimAfter(start));
      List<OutboxClaim> combined = new java.util.ArrayList<>(first.get());
      combined.addAll(second.get());
      assertThat(combined)
          .extracting(OutboxClaim::eventId)
          .containsExactlyInAnyOrder(firstEvent, secondEvent);
    }
    migrationJdbc.update(
        "UPDATE agendamento.outbox_event SET lease_until = clock_timestamp() - interval '1 second' WHERE event_id = ?",
        secondEvent);

    OutboxWorker directWorker = new OutboxWorker(outbox, null, 1, 60, 8);
    AtomicInteger published = new AtomicInteger();
    assertThat(
            directWorker.processBatch(
                event -> {
                  assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                      .isFalse();
                  published.incrementAndGet();
                  return DeliveryResult.DELIVERED;
                }))
        .isEqualTo(1);
    assertThat(published).hasValue(1);
  }

  @Test
  void consumerReceiptAndEffectShareTransactionAndTolerateRedelivery() {
    String table = "consumer_effect_" + UUID.randomUUID().toString().replace("-", "");
    migrationJdbc.execute("CREATE TABLE agendamento." + table + " (event_id UUID PRIMARY KEY)");
    migrationJdbc.execute(
        "GRANT SELECT, INSERT ON agendamento." + table + " TO agendamento_runtime");
    UUID eventId = UUID.randomUUID();
    try {
      assertThatThrownBy(
              () ->
                  consumer.process(
                      "agd005.test.consumer",
                      eventId,
                      () -> {
                        runtimeJdbc.update(
                            "INSERT INTO agendamento." + table + " VALUES (?)", eventId);
                        throw new IllegalStateException("crash after local effect before commit");
                      }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(effectCount(table, eventId)).isZero();
      assertThat(receiptCount("agd005.test.consumer", eventId)).isZero();

      assertThat(
              consumer.process(
                  "agd005.test.consumer",
                  eventId,
                  () ->
                      runtimeJdbc.update(
                          "INSERT INTO agendamento." + table + " VALUES (?)", eventId)))
          .isTrue();
      AtomicInteger duplicateEffect = new AtomicInteger();
      assertThat(
              consumer.process("agd005.test.consumer", eventId, duplicateEffect::incrementAndGet))
          .isFalse();
      assertThat(duplicateEffect).hasValue(0);
      assertThat(effectCount(table, eventId)).isEqualTo(1);
      assertThat(receiptCount("agd005.test.consumer", eventId)).isEqualTo(1);
    } finally {
      migrationJdbc.execute("DROP TABLE agendamento." + table);
    }
  }

  @Test
  void runtimeRoleHasOnlyRequiredNewGrantsAndAuditRemainsAppendOnly() {
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'agendamento.idempotency_record', 'SELECT') "
                    + "AND has_table_privilege(current_user, 'agendamento.idempotency_record', 'INSERT') "
                    + "AND has_table_privilege(current_user, 'agendamento.idempotency_record', 'UPDATE') "
                    + "AND NOT has_table_privilege(current_user, 'agendamento.idempotency_record', 'DELETE') "
                    + "AND NOT has_table_privilege(current_user, 'agendamento.idempotency_record', 'TRUNCATE') "
                    + "AND has_table_privilege(current_user, 'agendamento.outbox_event', 'SELECT') "
                    + "AND has_table_privilege(current_user, 'agendamento.outbox_event', 'INSERT') "
                    + "AND has_table_privilege(current_user, 'agendamento.outbox_event', 'UPDATE') "
                    + "AND NOT has_table_privilege(current_user, 'agendamento.outbox_event', 'DELETE') "
                    + "AND NOT has_table_privilege(current_user, 'agendamento.outbox_event', 'TRUNCATE') "
                    + "AND has_table_privilege(current_user, 'agendamento.processed_event', 'INSERT') "
                    + "AND has_table_privilege(current_user, 'agendamento.processed_event', 'SELECT') "
                    + "AND NOT has_table_privilege(current_user, 'agendamento.processed_event', 'UPDATE')",
                Boolean.class))
        .isTrue();
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'agendamento.audit_event', 'SELECT') "
                    + "AND has_table_privilege(current_user, 'agendamento.audit_event', 'INSERT') "
                    + "AND NOT has_table_privilege(current_user, 'agendamento.audit_event', 'UPDATE') "
                    + "AND NOT has_table_privilege(current_user, 'agendamento.audit_event', 'DELETE') "
                    + "AND NOT has_table_privilege(current_user, 'agendamento.audit_event', 'TRUNCATE')",
                Boolean.class))
        .isTrue();
    assertThatThrownBy(() -> runtimeJdbc.update("DELETE FROM agendamento.audit_event"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> runtimeJdbc.execute("TRUNCATE agendamento.audit_event"))
        .isInstanceOf(RuntimeException.class);
  }

  private List<OutboxClaim> claimAfter(CyclicBarrier barrier) throws Exception {
    barrier.await();
    return outbox.claim(1, Duration.ofMinutes(1));
  }

  private boolean awaitUniqueKeyWait() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting =
          migrationJdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE usename = 'agendamento_app' "
                  + "AND wait_event_type = 'Lock' AND query LIKE '%idempotency_record%'",
              Integer.class);
      if (waiting != null && waiting > 0) {
        return true;
      }
      Thread.sleep(25);
    }
    return false;
  }

  private void assertAtomicRenameAbsent(UUID tenantId, String key) {
    assertThat(tenantVersion(tenantId)).isZero();
    assertThat(count("audit_event", "tenant_id", tenantId, "TENANT_RENAMED")).isZero();
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND key_digest = ?",
                Integer.class,
                tenantId,
                IdempotencyKey.digest(key)))
        .isZero();
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.outbox_event WHERE tenant_id = ? AND event_type = 'TENANT_RENAMED'",
                Integer.class,
                tenantId))
        .isZero();
  }

  private void postponeOtherPendingEvents(UUID... keep) {
    String placeholders = String.join(",", java.util.Collections.nCopies(keep.length, "?"));
    migrationJdbc.update(
        "UPDATE agendamento.outbox_event SET next_attempt_at = clock_timestamp() + interval '1 hour' "
            + "WHERE state IN ('PENDING', 'RETRY') AND event_id NOT IN ("
            + placeholders
            + ")",
        (Object[]) keep);
  }

  private UUID eventFor(UUID operationId) {
    return runtimeJdbc.queryForObject(
        "SELECT event_id FROM agendamento.outbox_event WHERE operation_id = ?",
        UUID.class,
        operationId);
  }

  private long count(String table, String column, UUID tenantId, String action) {
    return runtimeJdbc.queryForObject(
        "SELECT count(*) FROM agendamento."
            + table
            + " WHERE "
            + column
            + " = ? AND "
            + (table.equals("audit_event") ? "action" : "event_type")
            + " = ?",
        Long.class,
        tenantId,
        action);
  }

  private long tenantVersion(UUID tenantId) {
    return runtimeJdbc.queryForObject(
        "SELECT version FROM agendamento.tenant WHERE tenant_id = ?", Long.class, tenantId);
  }

  private String outboxState(UUID eventId) {
    return runtimeJdbc.queryForObject(
        "SELECT state FROM agendamento.outbox_event WHERE event_id = ?", String.class, eventId);
  }

  private String tenantName(UUID tenantId) {
    return runtimeJdbc.queryForObject(
        "SELECT name FROM agendamento.tenant WHERE tenant_id = ?", String.class, tenantId);
  }

  private long effectCount(String table, UUID eventId) {
    return runtimeJdbc.queryForObject(
        "SELECT count(*) FROM agendamento." + table + " WHERE event_id = ?", Long.class, eventId);
  }

  private long receiptCount(String consumerName, UUID eventId) {
    return runtimeJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.processed_event WHERE consumer_name = ? AND event_id = ?",
        Long.class,
        consumerName,
        eventId);
  }

  private void installRejectTrigger(
      String table, String trigger, String function, String predicate) {
    migrationJdbc.execute(
        "CREATE OR REPLACE FUNCTION agendamento."
            + function
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN IF "
            + predicate
            + " THEN RAISE EXCEPTION 'simulated persistence failure'; END IF; RETURN NEW; END $$");
    migrationJdbc.execute(
        "CREATE TRIGGER "
            + trigger
            + " BEFORE INSERT ON agendamento."
            + table
            + " FOR EACH ROW EXECUTE FUNCTION agendamento."
            + function
            + "()");
  }

  private void removeTrigger(String table, String trigger, String function) {
    migrationJdbc.execute("DROP TRIGGER IF EXISTS " + trigger + " ON agendamento." + table);
    migrationJdbc.execute("DROP FUNCTION IF EXISTS agendamento." + function + "()");
  }

  private Tenant tenant() {
    return createTenant.execute("Tenant test " + UUID.randomUUID(), "agd005-" + suffix(), "UTC");
  }

  private AuthenticatedAdmin admin(UUID tenantId) {
    UUID adminId = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_user (user_id, identity_issuer, identity_subject) VALUES (?, ?, ?)",
        adminId,
        ISSUER,
        subject);
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_tenant_membership (user_id, tenant_id, role) VALUES (?, ?, 'TENANT_ADMIN')",
        adminId,
        tenantId);
    return new AuthenticatedAdmin() {
      @Override
      public UUID adminUserId() {
        return adminId;
      }

      @Override
      public String issuer() {
        return ISSUER;
      }

      @Override
      public String subject() {
        return subject;
      }

      @Override
      public String role() {
        return "TENANT_ADMIN";
      }

      @Override
      public UUID tenantId() {
        return tenantId;
      }
    };
  }

  private static CommandResultCodec<String> stringCodec() {
    return new CommandResultCodec<>() {
      @Override
      public String encode(String result) {
        return "\"" + result + "\"";
      }

      @Override
      public String decode(String json) {
        return json.substring(1, json.length() - 1);
      }
    };
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timeout aguardando sincronização de teste");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
