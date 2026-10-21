package com.appagendamento.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.subscriptions.api.CommercialEligibilityIdentitySource;
import com.appagendamento.subscriptions.api.VerifiedCommercialIdentity;
import com.appagendamento.subscriptions.application.SubscriptionTrialLifecycle;
import com.appagendamento.support.PostgresTestEnvironment;
import com.appagendamento.tenant.api.TenantTrialProvisioner;
import com.appagendamento.tenant.application.CreateTenant;
import com.appagendamento.tenant.domain.Tenant;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = "app.subscriptions.trial-expiry-worker.enabled=false")
class SubscriptionTrialIT {

  private static final Instant NOW = Instant.parse("2026-10-21T15:00:00Z");

  @Autowired private CreateTenant createTenant;
  @Autowired private TenantTrialProvisioner trialProvisioner;
  @Autowired private SubscriptionTrialLifecycle lifecycle;
  @Autowired private JdbcTemplate jdbc;
  @MockitoBean private CommercialEligibilityIdentitySource identities;
  @MockitoBean private Clock clock;
  @MockitoSpyBean private AuditEventRecorder audit;

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void configureDeterministicAdapters() {
    reset(identities, clock, audit);
    when(clock.instant()).thenReturn(NOW);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(clock.withZone(any())).thenReturn(clock);
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

  private static VerifiedCommercialIdentity identity(String value) {
    return new VerifiedCommercialIdentity(value);
  }
}
