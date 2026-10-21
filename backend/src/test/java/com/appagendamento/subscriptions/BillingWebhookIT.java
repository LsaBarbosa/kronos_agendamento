package com.appagendamento.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.subscriptions.api.AuthenticatedPaymentEvent;
import com.appagendamento.subscriptions.api.BillingOffer;
import com.appagendamento.subscriptions.api.BillingOfferCatalog;
import com.appagendamento.subscriptions.api.HostedCheckoutProvider;
import com.appagendamento.subscriptions.api.HostedCheckoutProvider.CheckoutSession;
import com.appagendamento.subscriptions.application.AdmitPaymentWebhook;
import com.appagendamento.subscriptions.application.BillingRepository;
import com.appagendamento.subscriptions.application.StartSubscriptionCheckout;
import com.appagendamento.subscriptions.domain.CheckoutOperation;
import com.appagendamento.subscriptions.domain.PlanCode;
import com.appagendamento.support.PostgresTestEnvironment;
import com.appagendamento.tenant.application.CreateTenant;
import com.appagendamento.tenant.domain.Tenant;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "app.subscriptions.trial-expiry-worker.enabled=false")
class BillingWebhookIT {

  private static final String PROVIDER = "controlled-provider";
  private static final String ACCOUNT = "controlled-account";
  private static final Instant NOW = Instant.parse("2026-10-21T16:00:00Z");

  @Autowired private CreateTenant createTenant;
  @Autowired private BillingRepository billing;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private AdmitPaymentWebhook webhook;
  @Autowired private StartSubscriptionCheckout startCheckout;
  @MockitoBean private HostedCheckoutProvider checkoutProvider;
  @MockitoBean private BillingOfferCatalog offerCatalog;
  @MockitoBean private AdminAccessPolicy adminAccessPolicy;

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @Test
  void authenticatedEvidenceArrivingBeforeProviderAssociationIsAppliedOnce() {
    Tenant tenant = tenant("billing-before-association");
    CheckoutOperation operation = operation(tenant.tenantId());
    billing.createCheckout(operation);
    UUID claim =
        billing.claimProviderCall(tenant.tenantId(), operation.operationId()).orElseThrow();
    String reference = "provider-ref-" + operation.operationId();
    AuthenticatedPaymentEvent event = confirmed("event-first", reference, 1, 1);

    assertThat(billing.recordAuthenticatedEvent(event)).isTrue();
    assertThat(subscriptionCount(tenant.tenantId())).isZero();

    billing.markCheckoutReady(
        tenant.tenantId(),
        operation.operationId(),
        claim,
        PROVIDER,
        ACCOUNT,
        new CheckoutSession(reference, "https://checkout.example.invalid/session"));

    assertThat(status(tenant.tenantId())).isEqualTo("ACTIVE:1");
    assertThat(eventDisposition("event-first")).isEqualTo("APPLIED");
    assertThat(eventOperation("event-first")).isEqualTo(operation.operationId());
    assertThat(historyCount(tenant.tenantId())).isEqualTo(1);
    assertThat(outboxCount(tenant.tenantId())).isEqualTo(1);
    assertThat(paymentAuditCount(tenant.tenantId())).isEqualTo(1);
    assertThat(billing.recordAuthenticatedEvent(event)).isFalse();
    assertThat(historyCount(tenant.tenantId())).isEqualTo(1);
    assertThat(outboxCount(tenant.tenantId())).isEqualTo(1);
    assertThat(paymentAuditCount(tenant.tenantId())).isEqualTo(1);
  }

  @Test
  void checkoutRecoveryReturnsPersistedConfirmationWhenInboxEvidenceWasEarly() {
    Tenant tenant = tenant("billing-early-confirmation-recovery");
    AuthenticatedAdmin admin = admin(tenant.tenantId());
    when(adminAccessPolicy.isCurrent(admin)).thenReturn(true);
    when(checkoutProvider.isConfigured()).thenReturn(true);
    when(checkoutProvider.providerCode()).thenReturn(PROVIDER);
    when(checkoutProvider.providerAccountId()).thenReturn(ACCOUNT);
    when(offerCatalog.findApprovedOffer(PlanCode.MONTHLY))
        .thenReturn(
            java.util.Optional.of(new BillingOffer(PlanCode.MONTHLY, 1, "XTS", "terms", "offer")));
    when(checkoutProvider.create(any())).thenThrow(new IllegalStateException("synthetic timeout"));

    var first =
        startCheckout.execute(
            admin, tenant.tenantId(), PlanCode.MONTHLY, 0, "checkout-early-event-key");
    assertThat(first.state()).isEqualTo("OUTCOME_UNKNOWN");
    UUID operationId = first.operationId();
    String reference = "early-confirmation-" + operationId;
    AuthenticatedPaymentEvent earlyConfirmation =
        confirmed("early-confirmation-event", reference, 1, 1);
    assertThat(billing.recordAuthenticatedEvent(earlyConfirmation)).isTrue();
    assertThat(eventDisposition("early-confirmation-event")).isEqualTo("UNLINKED");

    when(checkoutProvider.findByOperationId(operationId))
        .thenReturn(
            java.util.Optional.of(
                new CheckoutSession(reference, "https://checkout.example.invalid/session")));
    var recovered =
        startCheckout.execute(
            admin, tenant.tenantId(), PlanCode.MONTHLY, 0, "checkout-early-event-key");

    assertThat(recovered.replayed()).isTrue();
    assertThat(recovered.operationId()).isEqualTo(operationId);
    assertThat(recovered.state()).isEqualTo("CONFIRMED");
    assertThat(recovered.redirectUrl()).isNull();
    assertThat(status(tenant.tenantId())).isEqualTo("ACTIVE:1");
    assertThat(eventDisposition("early-confirmation-event")).isEqualTo("APPLIED");
    assertThat(eventOperation("early-confirmation-event")).isEqualTo(operationId);
    assertThat(historyCount(tenant.tenantId())).isEqualTo(1);
    assertThat(paymentAuditCount(tenant.tenantId())).isEqualTo(1);
    assertThat(outboxCount(tenant.tenantId())).isEqualTo(1);
    var repeated =
        startCheckout.execute(
            admin, tenant.tenantId(), PlanCode.MONTHLY, 0, "checkout-early-event-key");
    assertThat(repeated.state()).isEqualTo("CONFIRMED");
    assertThat(repeated.redirectUrl()).isNull();
    assertThat(historyCount(tenant.tenantId())).isEqualTo(1);
    assertThat(paymentAuditCount(tenant.tenantId())).isEqualTo(1);
    assertThat(outboxCount(tenant.tenantId())).isEqualTo(1);
  }

  @Test
  void duplicateAndOutOfOrderEventsCannotRegressConfirmedSubscription() {
    Tenant tenant = tenant("billing-event-order");
    CheckoutOperation operation = operation(tenant.tenantId());
    billing.createCheckout(operation);
    UUID claim =
        billing.claimProviderCall(tenant.tenantId(), operation.operationId()).orElseThrow();
    String reference = "provider-ref-" + operation.operationId();
    billing.markCheckoutReady(
        tenant.tenantId(),
        operation.operationId(),
        claim,
        PROVIDER,
        ACCOUNT,
        new CheckoutSession(reference, "https://checkout.example.invalid/session"));

    AuthenticatedPaymentEvent confirmed = confirmed("event-newer", reference, 2, 1);
    assertThat(billing.recordAuthenticatedEvent(confirmed)).isTrue();
    assertThat(billing.recordAuthenticatedEvent(confirmed)).isFalse();
    assertThat(
            billing.recordAuthenticatedEvent(
                new AuthenticatedPaymentEvent(
                    PROVIDER,
                    ACCOUNT,
                    "event-older",
                    reference,
                    1,
                    "DECLINED",
                    1,
                    "XTS",
                    NOW.minusSeconds(60),
                    null)))
        .isTrue();

    assertThat(status(tenant.tenantId())).isEqualTo("ACTIVE:1");
    assertThat(eventDisposition("event-older")).isEqualTo("OLDER");
    assertThat(historyCount(tenant.tenantId())).isEqualTo(1);
    assertThat(outboxCount(tenant.tenantId())).isEqualTo(1);
    assertThat(paymentAuditCount(tenant.tenantId())).isEqualTo(1);
  }

  @Test
  void paymentConfirmationUpdatesTrialSubscriptionUsingRuntimeRole() {
    Tenant tenant = tenant("billing-confirm-existing-trial");
    UUID subscriptionId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO agendamento.subscription
          (subscription_id, tenant_id, status, trial_started_at, trial_ends_at, version)
        VALUES (?, ?, 'TRIALING', ?, ?, 1)
        """,
        subscriptionId,
        tenant.tenantId(),
        java.sql.Timestamp.from(NOW.minusSeconds(7 * 24 * 60 * 60L)),
        java.sql.Timestamp.from(NOW.plusSeconds(7 * 24 * 60 * 60L)));
    CheckoutOperation operation = operation(tenant.tenantId());
    billing.createCheckout(operation);
    UUID claim =
        billing.claimProviderCall(tenant.tenantId(), operation.operationId()).orElseThrow();
    String reference = "provider-ref-" + operation.operationId();
    billing.markCheckoutReady(
        tenant.tenantId(),
        operation.operationId(),
        claim,
        PROVIDER,
        ACCOUNT,
        new CheckoutSession(reference, "https://checkout.example.invalid/session"));

    assertThat(billing.recordAuthenticatedEvent(confirmed("existing-trial-paid", reference, 1, 1)))
        .isTrue();

    assertThat(status(tenant.tenantId())).isEqualTo("ACTIVE:2");
    assertThat(
            jdbc.queryForObject(
                "SELECT plan_code || ':' || (paid_through_at IS NOT NULL)::text FROM agendamento.subscription WHERE tenant_id = ?",
                String.class,
                tenant.tenantId()))
        .isEqualTo("MONTHLY:true");
    assertThat(historyCount(tenant.tenantId())).isEqualTo(1);
    assertThat(paymentAuditCount(tenant.tenantId())).isEqualTo(1);
    assertThat(outboxCount(tenant.tenantId())).isEqualTo(1);
  }

  @Test
  void invalidWebhookEvidenceDoesNotPersistAnything() {
    int before =
        jdbc.queryForObject("SELECT count(*) FROM agendamento.billing_event_inbox", Integer.class);

    assertThatThrownBy(() -> webhook.execute("unconfigured", new byte[] {1, 2, 3}, "invalid"))
        .isInstanceOf(
            com.appagendamento.subscriptions.api.PaymentIntegrationUnavailableException.class);
    assertThatThrownBy(() -> webhook.execute("unconfigured", new byte[65_537], "invalid"))
        .isInstanceOf(
            com.appagendamento.subscriptions.application.PaymentWebhookTooLargeException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.billing_event_inbox", Integer.class))
        .isEqualTo(before);
  }

  @Test
  void checkoutOperationAndProviderReferenceCannotCrossTenant() {
    Tenant owner = tenant("billing-owner");
    Tenant other = tenant("billing-other");
    CheckoutOperation operation = operation(owner.tenantId());
    billing.createCheckout(operation);

    assertThat(billing.findCheckout(other.tenantId(), operation.operationId())).isEmpty();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO agendamento.billing_event_inbox
                      (provider_code, provider_account_id, provider_event_id, provider_reference,
                       provider_sequence, outcome, amount_minor_units, currency, occurred_at,
                       tenant_id, operation_id, disposition)
                    VALUES (?, ?, ?, ?, 1, 'PENDING', 1, 'XTS', ?, ?, ?, 'PENDING')
                    """,
                    PROVIDER,
                    ACCOUNT,
                    "cross-tenant-event-" + UUID.randomUUID(),
                    "other-reference",
                    java.sql.Timestamp.from(NOW),
                    other.tenantId(),
                    operation.operationId()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void concurrentDistinctCheckoutIntentsCannotCreateTwoOpenOperationsForTenant() throws Exception {
    Tenant tenant = tenant("billing-concurrent-checkout");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> tryCreateCheckout(tenant.tenantId(), ready, start));
      var second = executor.submit(() -> tryCreateCheckout(tenant.tenantId(), ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(java.util.List.of(first.get(), second.get()))
          .containsExactlyInAnyOrder(true, false);
    }
  }

  @Test
  void paymentConfirmationRollsBackWhenTheTransactionalAuditCannotBeStored() throws Exception {
    Tenant tenant = tenant("billing-audit-rollback");
    CheckoutOperation operation = operation(tenant.tenantId());
    billing.createCheckout(operation);
    UUID claim =
        billing.claimProviderCall(tenant.tenantId(), operation.operationId()).orElseThrow();
    String reference = "provider-ref-" + operation.operationId();
    billing.markCheckoutReady(
        tenant.tenantId(),
        operation.operationId(),
        claim,
        PROVIDER,
        ACCOUNT,
        new CheckoutSession(reference, "https://checkout.example.invalid/session"));
    alterPaymentAuditConstraint(true);
    try {
      assertThatThrownBy(
              () -> billing.recordAuthenticatedEvent(confirmed("rollback-event", reference, 1, 1)))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    } finally {
      alterPaymentAuditConstraint(false);
    }

    assertThat(subscriptionCount(tenant.tenantId())).isZero();
    assertThat(historyCount(tenant.tenantId())).isZero();
    assertThat(paymentAuditCount(tenant.tenantId())).isZero();
    assertThat(outboxCount(tenant.tenantId())).isZero();
    assertThat(eventCount("rollback-event")).isZero();
  }

  private Tenant tenant(String prefix) {
    return createTenant.execute(
        "Billing " + prefix, prefix + "-" + UUID.randomUUID().toString().substring(0, 8), "UTC");
  }

  private static AuthenticatedAdmin admin(UUID tenantId) {
    UUID adminId = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    return new AuthenticatedAdmin() {
      @Override
      public UUID adminUserId() {
        return adminId;
      }

      @Override
      public String issuer() {
        return "https://identity.test/realms/agendamento";
      }

      @Override
      public String subject() {
        return subject;
      }

      @Override
      public UUID tenantId() {
        return tenantId;
      }

      @Override
      public String role() {
        return "TENANT_ADMIN";
      }
    };
  }

  private static CheckoutOperation operation(UUID tenantId) {
    return new CheckoutOperation(
        UUID.randomUUID(),
        tenantId,
        PlanCode.MONTHLY,
        1,
        "XTS",
        "synthetic-terms",
        "synthetic-offer-v1",
        0,
        "REQUESTED",
        PROVIDER,
        ACCOUNT,
        null,
        0,
        NOW);
  }

  private boolean tryCreateCheckout(UUID tenantId, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS))
      throw new IllegalStateException("Largada concorrente expirou");
    try {
      billing.createCheckout(operation(tenantId));
      return true;
    } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
      return false;
    }
  }

  private static AuthenticatedPaymentEvent confirmed(
      String eventId, String reference, long sequence, long amount) {
    return new AuthenticatedPaymentEvent(
        PROVIDER,
        ACCOUNT,
        eventId,
        reference,
        sequence,
        "CONFIRMED",
        amount,
        "XTS",
        NOW,
        NOW.plusSeconds(30L * 24 * 60 * 60));
  }

  private int subscriptionCount(UUID tenantId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM agendamento.subscription WHERE tenant_id = ?",
        Integer.class,
        tenantId);
  }

  private String status(UUID tenantId) {
    return jdbc.queryForObject(
        "SELECT status || ':' || version FROM agendamento.subscription WHERE tenant_id = ?",
        String.class,
        tenantId);
  }

  private int historyCount(UUID tenantId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM agendamento.subscription_history WHERE tenant_id = ? AND event_type = 'PAYMENT_CONFIRMED'",
        Integer.class,
        tenantId);
  }

  private int outboxCount(UUID tenantId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM agendamento.outbox_event WHERE tenant_id = ? AND event_type = 'SubscriptionReactivated'",
        Integer.class,
        tenantId);
  }

  private int paymentAuditCount(UUID tenantId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM agendamento.audit_event WHERE tenant_id = ? AND action = 'SUBSCRIPTION_PAYMENT_CONFIRMED'",
        Integer.class,
        tenantId);
  }

  private int eventCount(String eventId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM agendamento.billing_event_inbox WHERE provider_code = ? AND provider_account_id = ? AND provider_event_id = ?",
        Integer.class,
        PROVIDER,
        ACCOUNT,
        eventId);
  }

  private static void alterPaymentAuditConstraint(boolean add) throws Exception {
    try (var connection = PostgresTestEnvironment.migrationDataSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute("SET ROLE agendamento_audit_owner");
      statement.execute(
          add
              ? "ALTER TABLE agendamento.audit_event ADD CONSTRAINT ck_test_reject_payment_audit CHECK (action <> 'SUBSCRIPTION_PAYMENT_CONFIRMED') NOT VALID"
              : "ALTER TABLE agendamento.audit_event DROP CONSTRAINT ck_test_reject_payment_audit");
    }
  }

  private String eventDisposition(String eventId) {
    return jdbc.queryForObject(
        "SELECT disposition FROM agendamento.billing_event_inbox WHERE provider_code = ? AND provider_account_id = ? AND provider_event_id = ?",
        String.class,
        PROVIDER,
        ACCOUNT,
        eventId);
  }

  private UUID eventOperation(String eventId) {
    return jdbc.queryForObject(
        "SELECT operation_id FROM agendamento.billing_event_inbox WHERE provider_code = ? AND provider_account_id = ? AND provider_event_id = ?",
        UUID.class,
        PROVIDER,
        ACCOUNT,
        eventId);
  }
}
