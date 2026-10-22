package com.appagendamento.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.subscriptions.api.CommercialEligibilityIdentitySource;
import com.appagendamento.subscriptions.api.VerifiedCommercialIdentity;
import com.appagendamento.subscriptions.application.DunningAttemptReconciler;
import com.appagendamento.subscriptions.application.DunningChargeAuthorization;
import com.appagendamento.subscriptions.application.DunningChargeTransport;
import com.appagendamento.subscriptions.application.SubscriptionDunningRepository;
import com.appagendamento.subscriptions.application.SubscriptionDunningWorker;
import com.appagendamento.subscriptions.application.SubscriptionTrialLifecycle;
import com.appagendamento.support.PostgresTestEnvironment;
import com.appagendamento.tenant.api.TenantTrialProvisioner;
import com.appagendamento.tenant.application.CreateTenant;
import com.appagendamento.tenant.domain.Tenant;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(
    properties = {
      "app.subscriptions.trial-expiry-worker.enabled=false",
      "app.subscriptions.dunning-worker.enabled=false"
    })
class SubscriptionTrialIT {

  private static final Instant NOW = Instant.parse("2026-10-21T15:00:00Z");

  @Autowired private CreateTenant createTenant;
  @Autowired private TenantTrialProvisioner trialProvisioner;
  @Autowired private SubscriptionTrialLifecycle lifecycle;
  @Autowired private SubscriptionDunningWorker dunningWorker;
  @Autowired private SubscriptionDunningRepository dunningRepository;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private JdbcTemplate jdbc;
  private JdbcTemplate migrationJdbc;
  @MockitoBean private CommercialEligibilityIdentitySource identities;
  @MockitoBean private Clock clock;
  @MockitoBean private DunningChargeAuthorization chargeAuthorization;
  @MockitoBean private DunningChargeTransport chargeTransport;
  @MockitoBean private DunningAttemptReconciler attemptReconciler;
  @MockitoSpyBean private AuditEventRecorder audit;

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void configureDeterministicAdapters() {
    reset(identities, clock, audit, chargeAuthorization, chargeTransport, attemptReconciler);
    migrationJdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
    migrationJdbc.update(
        "TRUNCATE TABLE agendamento.billing_dunning_attempt, agendamento.billing_dunning_cycle CASCADE");
    when(clock.instant()).thenReturn(NOW);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(clock.withZone(any())).thenReturn(clock);
    when(chargeAuthorization.isAuthorized(any(), any(), any(), any())).thenReturn(false);
  }

  @Test
  void absentOrInconclusiveIdentityDoesNotGrantTrial() {
    when(identities.findVerifiedIdentity(any())).thenReturn(Optional.empty());

    Tenant tenant = createTenant("without-identity");

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.commercial_trial_grant WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId()))
        .isZero();
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isFalse();
  }

  @Test
  void grantPersistsFourteenDaysAndRecreatedTenantCannotReuseIdentity() {
    when(identities.findVerifiedIdentity(any()))
        .thenReturn(Optional.of(identity("stable-commercial-key-for-test-0001")));

    Tenant first = createTenant("trial-first");
    Tenant recreated = createTenant("trial-recreated");

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM agendamento.subscription WHERE tenant_id = ?",
                String.class,
                first.tenantId()))
        .isEqualTo("TRIALING");
    assertThat(
            jdbc.queryForObject(
                "SELECT trial_ends_at - trial_started_at = INTERVAL '14 days' "
                    + "FROM agendamento.subscription WHERE tenant_id = ?",
                Boolean.class,
                first.tenantId()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription WHERE tenant_id = ?",
                Integer.class,
                recreated.tenantId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.commercial_trial_grant "
                    + "WHERE tenant_id IN (?, ?) AND commercial_identity_digest IS NOT NULL",
                Integer.class,
                first.tenantId(),
                recreated.tenantId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record "
                    + "WHERE tenant_id = ? AND command_scope = 'subscriptions.trial.grant.v1' "
                    + "AND state = 'COMPLETED'",
                Integer.class,
                first.tenantId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription_history "
                    + "WHERE tenant_id = ? AND event_type = 'TRIAL_GRANTED'",
                Integer.class,
                first.tenantId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.audit_event "
                    + "WHERE tenant_id = ? AND action = 'SUBSCRIPTION_TRIAL_GRANTED'",
                Integer.class,
                first.tenantId()))
        .isEqualTo(1);

    trialProvisioner.provisionIfEligible(first.tenantId());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription_history "
                    + "WHERE tenant_id = ? AND event_type = 'TRIAL_GRANTED'",
                Integer.class,
                first.tenantId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.audit_event "
                    + "WHERE tenant_id = ? AND action = 'SUBSCRIPTION_TRIAL_GRANTED'",
                Integer.class,
                first.tenantId()))
        .isEqualTo(1);
  }

  @Test
  void equalityAtTrialEndDeniesNewBookingAndMaterializesSuspensionOnce() {
    when(identities.findVerifiedIdentity(any()))
        .thenReturn(Optional.of(identity("trial-expiration-key-for-test-0001")));
    Tenant tenant = createTenant("trial-expiration");
    Instant trialEndsAt =
        jdbc.queryForObject(
            "SELECT trial_ends_at FROM agendamento.subscription WHERE tenant_id = ?",
            (row, index) -> row.getTimestamp(1).toInstant(),
            tenant.tenantId());
    when(clock.instant()).thenReturn(trialEndsAt.minusNanos(1));
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM agendamento.subscription WHERE tenant_id = ?",
                String.class,
                tenant.tenantId()))
        .isEqualTo("TRIALING");
    when(clock.instant()).thenReturn(trialEndsAt);

    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isFalse();
    assertThat(lifecycle.materializeExpired(tenant.tenantId())).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT status || ':' || version FROM agendamento.subscription WHERE tenant_id = ?",
                String.class,
                tenant.tenantId()))
        .isEqualTo("SUSPENDED:2");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription_history "
                    + "WHERE tenant_id = ? AND event_type = 'TRIAL_EXPIRED'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.audit_event "
                    + "WHERE tenant_id = ? AND action = 'SUBSCRIPTION_TRIAL_EXPIRED'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(1);
  }

  @Test
  void timeAfterTrialEndIsDeniedAndSuspended() {
    when(identities.findVerifiedIdentity(any()))
        .thenReturn(Optional.of(identity("trial-after-end-key-for-test-00001")));
    Tenant tenant = createTenant("trial-after-end");
    when(clock.instant()).thenReturn(trialEndsAt(tenant.tenantId()).plusNanos(1));

    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isFalse();
    assertThat(status(tenant.tenantId())).isEqualTo("SUSPENDED:2");
  }

  @Test
  void overdueSubscriptionCreatesStableDunningCycleAndSuspendsAtProposedBoundary() {
    when(identities.findVerifiedIdentity(any())).thenReturn(Optional.empty());
    Tenant tenant = createTenant("dunning-cycle");
    UUID subscriptionId = paidSubscription(tenant.tenantId(), NOW.minusSeconds(1));

    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();
    assertThat(status(tenant.tenantId())).isEqualTo("PAST_DUE:2");
    Instant graceEnd = NOW.minusSeconds(1).plusSeconds(7 * 24 * 60 * 60L);
    java.sql.Timestamp persistedGraceEnd =
        jdbc.queryForObject(
            "SELECT grace_ends_at FROM agendamento.subscription WHERE subscription_id = ?",
            java.sql.Timestamp.class,
            subscriptionId);
    assertThat(persistedGraceEnd.toInstant()).isEqualTo(graceEnd);
    assertThat(
            jdbc.queryForObject(
                "SELECT array_agg(logical_sequence ORDER BY logical_sequence)::text FROM agendamento.billing_dunning_attempt a JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id) WHERE c.tenant_id = ?",
                String.class,
                tenant.tenantId()))
        .isEqualTo("{0,3,6}");
    assertThat(
            jdbc.queryForObject(
                "SELECT bool_and(state = 'SCHEDULED' AND technical_attempts = 0 AND claim_token IS NULL) FROM agendamento.billing_dunning_attempt WHERE tenant_id = ?",
                Boolean.class,
                tenant.tenantId()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription_history WHERE tenant_id = ? AND event_type = 'PAYMENT_PAST_DUE'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.audit_event WHERE tenant_id = ? AND action = 'SUBSCRIPTION_PAYMENT_PAST_DUE'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.outbox_event WHERE tenant_id = ? AND event_type = 'SubscriptionPastDue'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(1);

    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.billing_dunning_attempt WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(3);

    when(clock.instant()).thenReturn(graceEnd);
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isFalse();
    assertThat(status(tenant.tenantId())).isEqualTo("SUSPENDED:3");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription_history WHERE tenant_id = ? AND event_type = 'PAYMENT_SUSPENDED'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND state = 'CANCELLED'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(3);
  }

  @Test
  void concurrentBillingWorkersMaterializeOneCycleAndStableLogicalAttempts() throws Exception {
    when(identities.findVerifiedIdentity(any())).thenReturn(Optional.empty());
    Tenant tenant = createTenant("dunning-worker-race");
    paidSubscription(tenant.tenantId(), NOW.minusSeconds(1));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> materializeBillingAfter(start, ready));
      var second = executor.submit(() -> materializeBillingAfter(start, ready));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
    }

    assertThat(status(tenant.tenantId())).isEqualTo("PAST_DUE:2");
    assertThat(count("billing_dunning_cycle", tenant.tenantId())).isEqualTo(1);
    assertThat(count("billing_dunning_attempt", tenant.tenantId())).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT array_agg(logical_sequence ORDER BY logical_sequence)::text FROM agendamento.billing_dunning_attempt a JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id) WHERE c.tenant_id = ?",
                String.class,
                tenant.tenantId()))
        .isEqualTo("{0,3,6}");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription_history WHERE tenant_id = ? AND event_type = 'PAYMENT_PAST_DUE'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.outbox_event WHERE tenant_id = ? AND event_type = 'SubscriptionPastDue'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(1);
  }

  @Test
  void lateRecoverySkipsOnlyOlderNeverStartedAttemptAndKeepsFutureSchedule() {
    when(identities.findVerifiedIdentity(any())).thenReturn(Optional.empty());
    when(chargeAuthorization.isAuthorized(any(), any(), any(), any())).thenReturn(true);
    when(chargeTransport.submit(any()))
        .thenReturn(
            new DunningChargeTransport.TransportResult(
                DunningChargeTransport.Outcome.RETRYABLE_NO_EFFECT,
                "synthetic",
                null,
                "NO_EFFECT_CONFIRMED"));
    Tenant tenant = createTenant("late-dunning-recovery");
    UUID subscriptionId = paidSubscription(tenant.tenantId(), NOW.minus(Duration.ofDays(4)));
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();

    assertThat(dunningWorker.processBatch()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT a.state FROM agendamento.billing_dunning_attempt a JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id) WHERE c.tenant_id = ? AND a.logical_sequence = 0",
                String.class,
                tenant.tenantId()))
        .isEqualTo("SKIPPED_LATE_RECOVERY");
    assertThat(
            jdbc.queryForObject(
                "SELECT skip_reason FROM agendamento.billing_dunning_attempt a JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id) WHERE c.tenant_id = ? AND a.logical_sequence = 0",
                String.class,
                tenant.tenantId()))
        .isEqualTo("LATE_RECOVERY_OLDER_DUE");
    assertThat(
            jdbc.queryForObject(
                "SELECT a.state = 'RETRYABLE_NO_EFFECT' AND a.ever_started AND a.technical_attempts = 1 AND a.next_attempt_at = ? FROM agendamento.billing_dunning_attempt a JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id) WHERE c.tenant_id = ? AND a.logical_sequence = 3",
                Boolean.class,
                java.sql.Timestamp.from(NOW.plusSeconds(30)),
                tenant.tenantId()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT a.state = 'SCHEDULED' AND a.scheduled_at = ? FROM agendamento.billing_dunning_attempt a JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id) WHERE c.tenant_id = ? AND a.logical_sequence = 6",
                Boolean.class,
                java.sql.Timestamp.from(NOW.plus(Duration.ofDays(2))),
                tenant.tenantId()))
        .isTrue();
    verify(chargeTransport).submit(any());
    var metrics = dunningRepository.metricsSnapshot(NOW);
    assertThat(metrics.backlog()).isEqualTo(2);
    assertThat(metrics.overdue()).isZero();
    assertThat(metrics.skippedLateRecovery()).isEqualTo(1);
    assertThat(metrics.technicalAttempts()).isEqualTo(1);
    assertThat(count("billing_dunning_cycle", tenant.tenantId())).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.billing_dunning_attempt WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(3);
    assertThat(status(tenant.tenantId())).startsWith("PAST_DUE:");
  }

  @Test
  void missingTransportBlocksConfigurationWithoutStartingOrCountingCharge() {
    when(identities.findVerifiedIdentity(any())).thenReturn(Optional.empty());
    Tenant tenant = createTenant("dunning-no-transport");
    paidSubscription(tenant.tenantId(), NOW.minusSeconds(1));
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();
    StaticListableBeanFactory emptyAdapters = new StaticListableBeanFactory();
    SubscriptionDunningWorker workerWithoutAdapters =
        new SubscriptionDunningWorker(
            dunningRepository,
            emptyAdapters.getBeanProvider(DunningChargeTransport.class),
            emptyAdapters.getBeanProvider(DunningAttemptReconciler.class),
            meterRegistry,
            clock,
            10,
            60,
            5);

    assertThat(workerWithoutAdapters.processBatch()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT state = 'BLOCKED_CONFIGURATION' AND NOT ever_started AND technical_attempts = 0 AND claim_token IS NULL FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND logical_sequence = 0",
                Boolean.class,
                tenant.tenantId()))
        .isTrue();
    verify(chargeTransport, org.mockito.Mockito.never()).submit(any());
  }

  @Test
  void technicalRetryReusesPersistedProviderIdentity() {
    when(identities.findVerifiedIdentity(any())).thenReturn(Optional.empty());
    when(chargeAuthorization.isAuthorized(any(), any(), any(), any())).thenReturn(true);
    when(chargeTransport.submit(any()))
        .thenReturn(
            new DunningChargeTransport.TransportResult(
                DunningChargeTransport.Outcome.RETRYABLE_NO_EFFECT,
                "synthetic",
                null,
                "NO_EFFECT_CONFIRMED"),
            new DunningChargeTransport.TransportResult(
                DunningChargeTransport.Outcome.ACCEPTED, "synthetic", "controlled-ref", null));
    Tenant tenant = createTenant("dunning-technical-retry");
    paidSubscription(tenant.tenantId(), NOW.minusSeconds(1));
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();
    int firstBatch = dunningWorker.processBatch();
    assertThat(
            jdbc.queryForObject(
                "SELECT state = 'RETRYABLE_NO_EFFECT' AND technical_attempts = 1 AND next_attempt_at > ? FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND logical_sequence = 0",
                Boolean.class,
                java.sql.Timestamp.from(NOW),
                tenant.tenantId()))
        .isTrue();
    assertThat(firstBatch).isEqualTo(1);
    UUID logicalAttemptId = attemptId(tenant.tenantId(), 0);

    when(clock.instant()).thenReturn(NOW.plusSeconds(30));
    assertThat(dunningWorker.processBatch()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT state = 'ACCEPTED' AND technical_attempts = 2 AND external_idempotency_key = attempt_id AND provider_reference = 'controlled-ref' FROM agendamento.billing_dunning_attempt WHERE attempt_id = ?",
                Boolean.class,
                logicalAttemptId))
        .isTrue();
    var requests = org.mockito.ArgumentCaptor.forClass(DunningChargeTransport.ChargeRequest.class);
    verify(chargeTransport, times(2)).submit(requests.capture());
    assertThat(requests.getAllValues())
        .extracting(DunningChargeTransport.ChargeRequest::externalIdempotencyKey)
        .containsOnly(logicalAttemptId);
  }

  @Test
  void ambiguousAttemptBlocksLaterLogicalChargesAndIsReconciledWithoutParallelSend() {
    when(identities.findVerifiedIdentity(any())).thenReturn(Optional.empty());
    when(chargeAuthorization.isAuthorized(any(), any(), any(), any())).thenReturn(true);
    when(chargeTransport.submit(any()))
        .thenReturn(
            new DunningChargeTransport.TransportResult(
                DunningChargeTransport.Outcome.OUTCOME_UNKNOWN,
                "synthetic",
                null,
                "PROVIDER_TIMEOUT"));
    when(attemptReconciler.reconcile(any(), any(), any(), any()))
        .thenReturn(
            new DunningChargeTransport.TransportResult(
                DunningChargeTransport.Outcome.OUTCOME_UNKNOWN,
                "synthetic",
                null,
                "LOOKUP_UNAVAILABLE"));
    Tenant tenant = createTenant("dunning-ambiguous-blocks-next");
    paidSubscription(tenant.tenantId(), NOW.minus(Duration.ofDays(4)));
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();

    assertThat(dunningWorker.processBatch()).isEqualTo(2);
    UUID firstAttempt = attemptId(tenant.tenantId(), 3);
    assertThat(
            jdbc.queryForObject(
                "SELECT state = 'OUTCOME_UNKNOWN' AND ever_started AND external_idempotency_key = attempt_id FROM agendamento.billing_dunning_attempt WHERE attempt_id = ?",
                Boolean.class,
                firstAttempt))
        .isTrue();

    when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(3)));
    assertThat(dunningWorker.processBatch()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT state = 'OUTCOME_UNKNOWN' AND next_attempt_at > ? FROM agendamento.billing_dunning_attempt WHERE attempt_id = ?",
                Boolean.class,
                java.sql.Timestamp.from(NOW.plus(Duration.ofDays(3))),
                firstAttempt))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND logical_sequence = 6",
                String.class,
                tenant.tenantId()))
        .isEqualTo("SCHEDULED");
    verify(chargeTransport).submit(any());
    verify(attemptReconciler, times(2)).reconcile(any(), any(), any(), any());
    assertThat(count("billing_dunning_attempt", tenant.tenantId())).isEqualTo(3);
  }

  @Test
  void concurrentWorkerDoesNotDuplicateClaimWhileTransportIsInFlight() throws Exception {
    when(identities.findVerifiedIdentity(any())).thenReturn(Optional.empty());
    when(chargeAuthorization.isAuthorized(any(), any(), any(), any())).thenReturn(true);
    CountDownLatch sendStarted = new CountDownLatch(1);
    CountDownLatch releaseSend = new CountDownLatch(1);
    when(chargeTransport.submit(any()))
        .thenAnswer(
            invocation -> {
              sendStarted.countDown();
              assertThat(releaseSend.await(10, TimeUnit.SECONDS)).isTrue();
              return new DunningChargeTransport.TransportResult(
                  DunningChargeTransport.Outcome.ACCEPTED, "synthetic", "one-acceptance", null);
            });
    Tenant tenant = createTenant("dunning-concurrent-claim");
    paidSubscription(tenant.tenantId(), NOW.minusSeconds(1));
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(dunningWorker::processBatch);
      assertThat(sendStarted.await(10, TimeUnit.SECONDS)).isTrue();
      var second = executor.submit(dunningWorker::processBatch);
      assertThat(second.get(3, TimeUnit.SECONDS)).isZero();
      releaseSend.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      releaseSend.countDown();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND state = 'ACCEPTED' AND provider_reference = 'one-acceptance'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(1);
    verify(chargeTransport).submit(any());
  }

  @Test
  void expiredInFlightLeaseReconcilesWithoutLosingLateAcceptedResult() throws Exception {
    when(identities.findVerifiedIdentity(any())).thenReturn(Optional.empty());
    when(chargeAuthorization.isAuthorized(any(), any(), any(), any())).thenReturn(true);
    AtomicReference<Instant> controlledNow = new AtomicReference<>(NOW);
    when(clock.instant()).thenAnswer(invocation -> controlledNow.get());
    CountDownLatch sendStarted = new CountDownLatch(1);
    CountDownLatch releaseSend = new CountDownLatch(1);
    when(chargeTransport.submit(any()))
        .thenAnswer(
            invocation -> {
              sendStarted.countDown();
              assertThat(releaseSend.await(10, TimeUnit.SECONDS)).isTrue();
              return new DunningChargeTransport.TransportResult(
                  DunningChargeTransport.Outcome.ACCEPTED,
                  "synthetic",
                  "late-accepted-reference",
                  null);
            });
    when(attemptReconciler.reconcile(any(), any(), any(), any()))
        .thenReturn(
            new DunningChargeTransport.TransportResult(
                DunningChargeTransport.Outcome.OUTCOME_UNKNOWN,
                "synthetic",
                null,
                "LOOKUP_STILL_PENDING"));
    Tenant tenant = createTenant("dunning-expired-send-lease");
    paidSubscription(tenant.tenantId(), NOW.minusSeconds(1));
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();
    UUID logicalAttemptId = attemptId(tenant.tenantId(), 0);

    try (var executor = Executors.newSingleThreadExecutor()) {
      var originalWorker = executor.submit(dunningWorker::processBatch);
      assertThat(sendStarted.await(10, TimeUnit.SECONDS)).isTrue();
      controlledNow.set(NOW.plusSeconds(61));
      assertThat(dunningWorker.processBatch()).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT state = 'OUTCOME_UNKNOWN' AND ever_started AND external_idempotency_key = attempt_id FROM agendamento.billing_dunning_attempt WHERE attempt_id = ?",
                  Boolean.class,
                  logicalAttemptId))
          .isTrue();
      releaseSend.countDown();
      assertThat(originalWorker.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      releaseSend.countDown();
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT state = 'ACCEPTED' AND provider_reference = 'late-accepted-reference' AND technical_attempts = 1 AND send_claim_token IS NOT NULL FROM agendamento.billing_dunning_attempt WHERE attempt_id = ?",
                Boolean.class,
                logicalAttemptId))
        .isTrue();
    verify(chargeTransport).submit(any());
    verify(attemptReconciler).reconcile(any(), any(), any(), any());
  }

  @Test
  void inFlightResultRemainsPersistableAfterGraceSuspendsSubscription() throws Exception {
    when(identities.findVerifiedIdentity(any())).thenReturn(Optional.empty());
    when(chargeAuthorization.isAuthorized(any(), any(), any(), any())).thenReturn(true);
    CountDownLatch sendStarted = new CountDownLatch(1);
    CountDownLatch releaseSend = new CountDownLatch(1);
    when(chargeTransport.submit(any()))
        .thenAnswer(
            invocation -> {
              sendStarted.countDown();
              assertThat(releaseSend.await(10, TimeUnit.SECONDS)).isTrue();
              return new DunningChargeTransport.TransportResult(
                  DunningChargeTransport.Outcome.ACCEPTED,
                  "synthetic",
                  "accepted-after-suspension",
                  null);
            });
    Tenant tenant = createTenant("dunning-inflight-suspend");
    UUID subscriptionId = paidSubscription(tenant.tenantId(), NOW.minusSeconds(1));
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();
    AtomicReference<Instant> controlledNow = new AtomicReference<>(NOW);
    when(clock.instant()).thenAnswer(invocation -> controlledNow.get());
    try (var executor = Executors.newSingleThreadExecutor()) {
      var processing = executor.submit(dunningWorker::processBatch);
      assertThat(sendStarted.await(10, TimeUnit.SECONDS)).isTrue();
      Instant graceEnd = NOW.minusSeconds(1).plus(Duration.ofHours(168));
      controlledNow.set(graceEnd);
      assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isFalse();
      assertThat(status(tenant.tenantId())).isEqualTo("SUSPENDED:3");
      releaseSend.countDown();
      assertThat(processing.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      releaseSend.countDown();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT a.state = 'ACCEPTED' AND a.provider_reference = 'accepted-after-suspension' AND a.ever_started FROM agendamento.billing_dunning_attempt a JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id) WHERE c.tenant_id = ? AND a.logical_sequence = 0",
                Boolean.class,
                tenant.tenantId()))
        .isTrue();
    assertThat(status(tenant.tenantId())).isEqualTo("SUSPENDED:3");
    assertThat(count("billing_dunning_cycle", tenant.tenantId())).isEqualTo(1);
    assertThat(subscriptionId).isNotNull();
  }

  @Test
  void dunningAuditFailureRollsBackStatusCycleHistoryAndOutbox() {
    when(identities.findVerifiedIdentity(any())).thenReturn(Optional.empty());
    Tenant tenant = createTenant("dunning-rollback");
    paidSubscription(tenant.tenantId(), NOW.minusSeconds(1));
    doAnswer(
            invocation -> {
              AuditEvent event = invocation.getArgument(0);
              if (event.action().equals("SUBSCRIPTION_PAYMENT_PAST_DUE")) {
                throw new IllegalStateException("controlled dunning audit failure");
              }
              return null;
            })
        .when(audit)
        .record(any(AuditEvent.class));

    assertThatThrownBy(() -> lifecycle.allowsNewPublicAppointments(tenant.tenantId()))
        .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
        .hasRootCauseMessage("controlled dunning audit failure");
    assertThat(status(tenant.tenantId())).isEqualTo("ACTIVE:1");
    assertThat(count("billing_dunning_cycle", tenant.tenantId())).isZero();
    assertThat(count("billing_dunning_attempt", tenant.tenantId())).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription_history WHERE tenant_id = ? AND event_type = 'PAYMENT_PAST_DUE'",
                Integer.class,
                tenant.tenantId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.outbox_event WHERE tenant_id = ? AND event_type = 'SubscriptionPastDue'",
                Integer.class,
                tenant.tenantId()))
        .isZero();
  }

  @Test
  void concurrentExpiryMaterializesOneTransitionAndOneAudit() throws Exception {
    when(identities.findVerifiedIdentity(any()))
        .thenReturn(Optional.of(identity("trial-worker-race-key-for-test-0001")));
    Tenant tenant = createTenant("trial-worker-race");
    when(clock.instant()).thenReturn(trialEndsAt(tenant.tenantId()));
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> expireAfter(start, tenant.tenantId()));
      var second = executor.submit(() -> expireAfter(start, tenant.tenantId()));
      start.countDown();
      boolean firstChanged = first.get(15, TimeUnit.SECONDS);
      boolean secondChanged = second.get(15, TimeUnit.SECONDS);
      assertThat((firstChanged ? 1 : 0) + (secondChanged ? 1 : 0)).isEqualTo(1);
    }
    assertThat(status(tenant.tenantId())).isEqualTo("SUSPENDED:2");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription_history "
                    + "WHERE tenant_id = ? AND event_type = 'TRIAL_EXPIRED'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.audit_event "
                    + "WHERE tenant_id = ? AND action = 'SUBSCRIPTION_TRIAL_EXPIRED'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(1);
  }

  @Test
  void concurrentTenantsCannotConsumeSameCommercialTrialTwice() throws Exception {
    when(identities.findVerifiedIdentity(any()))
        .thenReturn(Optional.of(identity("concurrent-commercial-key-for-test-01")));
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> createAfter(start, "trial-race-one"));
      var second = executor.submit(() -> createAfter(start, "trial-race-two"));
      start.countDown();
      Tenant one = first.get(15, TimeUnit.SECONDS);
      Tenant two = second.get(15, TimeUnit.SECONDS);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM agendamento.subscription WHERE tenant_id IN (?, ?)",
                  Integer.class,
                  one.tenantId(),
                  two.tenantId()))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM agendamento.commercial_trial_grant "
                      + "WHERE tenant_id IN (?, ?) AND commercial_identity_digest IS NOT NULL",
                  Integer.class,
                  one.tenantId(),
                  two.tenantId()))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM agendamento.idempotency_record "
                      + "WHERE tenant_id IN (?, ?) AND command_scope = 'subscriptions.trial.grant.v1' "
                      + "AND state = 'COMPLETED'",
                  Integer.class,
                  one.tenantId(),
                  two.tenantId()))
          .isEqualTo(2);
    }
  }

  @Test
  void historyCannotReferenceSubscriptionFromAnotherTenant() {
    when(identities.findVerifiedIdentity(any()))
        .thenReturn(Optional.of(identity("history-tenant-binding-key-000001")), Optional.empty());
    Tenant trialTenant = createTenant("history-trial-tenant");
    Tenant otherTenant = createTenant("history-other-tenant");
    UUID subscriptionId =
        jdbc.queryForObject(
            "SELECT subscription_id FROM agendamento.subscription WHERE tenant_id = ?",
            UUID.class,
            trialTenant.tenantId());

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO agendamento.subscription_history "
                        + "(event_id, tenant_id, subscription_id, event_type, from_status, to_status, event_at, version, operation_id) "
                        + "VALUES (?, ?, ?, 'TRIAL_GRANTED', NULL, 'TRIALING', ?, 1, ?)",
                    UUID.randomUUID(),
                    otherTenant.tenantId(),
                    subscriptionId,
                    java.sql.Timestamp.from(NOW),
                    UUID.randomUUID()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription_history "
                    + "WHERE tenant_id = ? AND subscription_id = ?",
                Integer.class,
                otherTenant.tenantId(),
                subscriptionId))
        .isZero();
  }

  @Test
  void auditFailureRollsBackTenantTrialGrantHistoryAndReceipt() {
    when(identities.findVerifiedIdentity(any()))
        .thenReturn(Optional.of(identity("rollback-commercial-key-for-test-0001")));
    doAnswer(
            invocation -> {
              AuditEvent event = invocation.getArgument(0);
              if (event.action().equals("SUBSCRIPTION_TRIAL_GRANTED")) {
                throw new IllegalStateException("controlled audit persistence failure");
              }
              return null;
            })
        .when(audit)
        .record(any(AuditEvent.class));
    String slug = "trial-rollback-" + UUID.randomUUID().toString().substring(0, 8);
    int subscriptionsBefore =
        jdbc.queryForObject("SELECT count(*) FROM agendamento.subscription", Integer.class);
    int grantsBefore =
        jdbc.queryForObject(
            "SELECT count(*) FROM agendamento.commercial_trial_grant", Integer.class);
    int historyBefore =
        jdbc.queryForObject("SELECT count(*) FROM agendamento.subscription_history", Integer.class);
    int receiptsBefore =
        jdbc.queryForObject("SELECT count(*) FROM agendamento.idempotency_record", Integer.class);

    assertThatThrownBy(() -> createTenant.execute("Trial rollback", slug, "UTC"))
        .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
        .hasRootCauseMessage("controlled audit persistence failure");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.tenant WHERE slug = ?", Integer.class, slug))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.commercial_trial_grant", Integer.class))
        .isEqualTo(grantsBefore);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM agendamento.subscription", Integer.class))
        .isEqualTo(subscriptionsBefore);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription_history", Integer.class))
        .isEqualTo(historyBefore);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record", Integer.class))
        .isEqualTo(receiptsBefore);
  }

  private Tenant createAfter(CountDownLatch start, String slug) throws Exception {
    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race latch timed out");
    return createTenant(slug);
  }

  private boolean expireAfter(CountDownLatch start, UUID tenantId) throws Exception {
    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race latch timed out");
    return lifecycle.materializeExpired(tenantId);
  }

  private int materializeBillingAfter(CountDownLatch start, CountDownLatch ready) throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS))
      throw new IllegalStateException("Billing worker race latch timed out");
    return lifecycle.materializeBillingBatch(10);
  }

  private Instant trialEndsAt(UUID tenantId) {
    return jdbc.queryForObject(
        "SELECT trial_ends_at FROM agendamento.subscription WHERE tenant_id = ?",
        (row, index) -> row.getTimestamp(1).toInstant(),
        tenantId);
  }

  private String status(UUID tenantId) {
    return jdbc.queryForObject(
        "SELECT status || ':' || version FROM agendamento.subscription WHERE tenant_id = ?",
        String.class,
        tenantId);
  }

  private Tenant createTenant(String prefix) {
    return createTenant.execute(
        "Trial " + prefix, prefix + "-" + UUID.randomUUID().toString().substring(0, 8), "UTC");
  }

  private UUID paidSubscription(UUID tenantId, Instant paidThroughAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'MONTHLY', ?, 1)",
        id,
        tenantId,
        java.sql.Timestamp.from(paidThroughAt));
    return id;
  }

  private int count(String table, UUID tenantId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM agendamento." + table + " WHERE tenant_id = ?",
        Integer.class,
        tenantId);
  }

  private UUID attemptId(UUID tenantId, int sequence) {
    return jdbc.queryForObject(
        "SELECT a.attempt_id FROM agendamento.billing_dunning_attempt a JOIN agendamento.billing_dunning_cycle c USING (cycle_id, tenant_id) WHERE c.tenant_id = ? AND a.logical_sequence = ?",
        UUID.class,
        tenantId,
        sequence);
  }

  private static VerifiedCommercialIdentity identity(String value) {
    return new VerifiedCommercialIdentity(value);
  }
}
