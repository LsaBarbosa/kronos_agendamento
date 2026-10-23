package com.appagendamento.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.appagendamento.subscriptions.application.DunningAttemptReconciler;
import com.appagendamento.subscriptions.application.DunningChargeAuthorization;
import com.appagendamento.subscriptions.application.DunningChargeTransport;
import com.appagendamento.subscriptions.application.StartSubscriptionCheckout;
import com.appagendamento.subscriptions.application.SubscriptionDunningRepository;
import com.appagendamento.subscriptions.application.SubscriptionDunningWorker;
import com.appagendamento.subscriptions.application.SubscriptionTrialLifecycle;
import com.appagendamento.subscriptions.domain.CheckoutOperation;
import com.appagendamento.subscriptions.domain.PlanCode;
import com.appagendamento.subscriptions.domain.Subscription;
import com.appagendamento.support.PostgresTestEnvironment;
import com.appagendamento.tenant.application.CreateTenant;
import com.appagendamento.tenant.domain.Tenant;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    properties = {
      "app.subscriptions.trial-expiry-worker.enabled=false",
      "app.subscriptions.dunning-worker.enabled=false"
    })
class BillingWebhookIT {

  private static final String PROVIDER = "controlled-provider";
  private static final String ACCOUNT = "controlled-account";
  private static final Instant NOW = Instant.parse("2026-10-21T16:00:00Z");

  @Autowired private CreateTenant createTenant;
  @Autowired private BillingRepository billing;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private AdmitPaymentWebhook webhook;
  @Autowired private StartSubscriptionCheckout startCheckout;
  @Autowired private SubscriptionDunningRepository dunning;
  @Autowired private SubscriptionTrialLifecycle lifecycle;
  @Autowired private SubscriptionDunningWorker dunningWorker;
  @MockitoBean private HostedCheckoutProvider checkoutProvider;
  @MockitoBean private BillingOfferCatalog offerCatalog;
  @MockitoBean private DunningChargeAuthorization chargeAuthorization;
  @MockitoBean private DunningChargeTransport chargeTransport;
  @MockitoBean private DunningAttemptReconciler attemptReconciler;
  @MockitoBean private AdminAccessPolicy adminAccessPolicy;
  @MockitoBean private Clock clock;

  @BeforeEach
  void configureClock() {
    when(clock.instant()).thenReturn(NOW);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(clock.withZone(any())).thenReturn(clock);
  }

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
  void checkoutCannotStartNewLogicalChargeWhileDunningOutcomeIsUnresolved() {
    Tenant tenant = tenant("checkout-blocked-by-dunning-unknown");
    UUID subscriptionId = UUID.randomUUID();
    Instant paidThrough = NOW.minusSeconds(60);
    jdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'MONTHLY', ?, 1)",
        subscriptionId,
        tenant.tenantId(),
        java.sql.Timestamp.from(paidThrough));
    CheckoutOperation contractOperation = operation(tenant.tenantId());
    billing.createCheckout(contractOperation);
    persistContractTerms(tenant.tenantId(), subscriptionId, contractOperation);
    assertThat(
            dunning.markPastDue(
                new Subscription(
                    subscriptionId,
                    tenant.tenantId(),
                    "ACTIVE",
                    null,
                    null,
                    "MONTHLY",
                    paidThrough,
                    null,
                    1),
                NOW))
        .isTrue();
    isolateOtherDunningCycles(tenant.tenantId());
    when(chargeAuthorization.isAuthorized(any(), any(), any(), any())).thenReturn(true);
    when(chargeTransport.submit(any()))
        .thenReturn(
            new DunningChargeTransport.TransportResult(
                DunningChargeTransport.Outcome.OUTCOME_UNKNOWN,
                PROVIDER,
                null,
                "PROVIDER_TIMEOUT"));

    // The shared integration database may contain unrelated due rows. Assert
    // the target attempt's durable result below, not the global batch size.
    dunningWorker.processBatch();
    UUID attemptId =
        jdbc.queryForObject(
            "SELECT attempt_id FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND logical_sequence = 0",
            UUID.class,
            tenant.tenantId());
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM agendamento.billing_dunning_attempt WHERE attempt_id = ?",
                String.class,
                attemptId))
        .isEqualTo("OUTCOME_UNKNOWN");

    Instant graceEnd = paidThrough.plus(Duration.ofHours(168));
    when(clock.instant()).thenReturn(graceEnd);
    assertThat(
            dunning.suspendPastDue(
                new Subscription(
                    subscriptionId,
                    tenant.tenantId(),
                    "PAST_DUE",
                    null,
                    null,
                    "MONTHLY",
                    paidThrough,
                    graceEnd,
                    2),
                graceEnd))
        .isTrue();
    assertThat(status(tenant.tenantId())).isEqualTo("SUSPENDED:3");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM agendamento.billing_dunning_attempt WHERE attempt_id = ?",
                String.class,
                attemptId))
        .isEqualTo("OUTCOME_UNKNOWN");

    AuthenticatedAdmin admin = admin(tenant.tenantId());
    when(adminAccessPolicy.isCurrent(admin)).thenReturn(true);
    when(checkoutProvider.isConfigured()).thenReturn(true);
    when(offerCatalog.findApprovedOffer(PlanCode.MONTHLY))
        .thenReturn(
            java.util.Optional.of(new BillingOffer(PlanCode.MONTHLY, 1, "XTS", "terms", "offer")));

    assertThatThrownBy(
            () ->
                startCheckout.execute(
                    admin,
                    tenant.tenantId(),
                    PlanCode.MONTHLY,
                    3,
                    "new-checkout-after-ambiguous-dunning"))
        .isInstanceOf(
            com.appagendamento.subscriptions.application.SubscriptionCheckoutConflictException
                .class)
        .hasMessage("DUNNING_PAYMENT_OUTCOME_UNRESOLVED");

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.billing_checkout_operation WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'subscriptions.checkout.v1'",
                Integer.class,
                tenant.tenantId()))
        .isZero();
    verify(checkoutProvider, never()).create(any());
  }

  @Test
  void checkoutAndDunningSendAuthorizationCannotStartParallelLogicalCharges() {
    Tenant claimedTenant = tenant("checkout-races-claimed-dunning");
    UUID claimedSubscription = UUID.randomUUID();
    Instant paidThrough = NOW.minusSeconds(60);
    jdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'MONTHLY', ?, 1)",
        claimedSubscription,
        claimedTenant.tenantId(),
        java.sql.Timestamp.from(paidThrough));
    assertThat(
            dunning.markPastDue(
                new Subscription(
                    claimedSubscription,
                    claimedTenant.tenantId(),
                    "ACTIVE",
                    null,
                    null,
                    "MONTHLY",
                    paidThrough,
                    null,
                    1),
                NOW))
        .isTrue();
    var claimedAttempt = dunning.claimNextAttempt(Duration.ofMinutes(1), false).orElseThrow();
    AuthenticatedAdmin claimedAdmin = admin(claimedTenant.tenantId());
    when(adminAccessPolicy.isCurrent(claimedAdmin)).thenReturn(true);
    when(checkoutProvider.isConfigured()).thenReturn(true);
    when(offerCatalog.findApprovedOffer(PlanCode.MONTHLY))
        .thenReturn(
            java.util.Optional.of(new BillingOffer(PlanCode.MONTHLY, 1, "XTS", "terms", "offer")));

    assertThatThrownBy(
            () ->
                startCheckout.execute(
                    claimedAdmin,
                    claimedTenant.tenantId(),
                    PlanCode.MONTHLY,
                    2,
                    "checkout-race-claimed-key"))
        .isInstanceOf(
            com.appagendamento.subscriptions.application.SubscriptionCheckoutConflictException
                .class)
        .hasMessage("DUNNING_PAYMENT_OUTCOME_UNRESOLVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND attempt_id = ?",
                String.class,
                claimedTenant.tenantId(),
                claimedAttempt.attemptId()))
        .isEqualTo("CLAIMED");
    verify(checkoutProvider, never()).create(any());

    Tenant checkoutTenant = tenant("dunning-races-open-checkout");
    UUID checkoutSubscription = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'MONTHLY', ?, 1)",
        checkoutSubscription,
        checkoutTenant.tenantId(),
        java.sql.Timestamp.from(paidThrough));
    assertThat(
            dunning.markPastDue(
                new Subscription(
                    checkoutSubscription,
                    checkoutTenant.tenantId(),
                    "ACTIVE",
                    null,
                    null,
                    "MONTHLY",
                    paidThrough,
                    null,
                    1),
                NOW))
        .isTrue();
    AuthenticatedAdmin checkoutAdmin = admin(checkoutTenant.tenantId());
    when(adminAccessPolicy.isCurrent(checkoutAdmin)).thenReturn(true);
    when(checkoutProvider.providerCode()).thenReturn(PROVIDER);
    when(checkoutProvider.providerAccountId()).thenReturn(ACCOUNT);
    when(checkoutProvider.create(any()))
        .thenReturn(
            new CheckoutSession(
                "open-checkout-" + checkoutTenant.tenantId(),
                "https://checkout.example.invalid/session"));
    var checkout =
        startCheckout.execute(
            checkoutAdmin,
            checkoutTenant.tenantId(),
            PlanCode.MONTHLY,
            2,
            "checkout-race-open-key");
    assertThat(checkout.state()).isEqualTo("CHECKOUT_READY");
    assertThat(dunning.claimNextAttempt(Duration.ofMinutes(1), false)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND logical_sequence = 0",
                String.class,
                checkoutTenant.tenantId()))
        .isEqualTo("SCHEDULED");
  }

  @Test
  void concurrentCheckoutAndDunningClaimChooseOnlyOneLogicalCharge() throws Exception {
    Tenant tenant = tenant("checkout-dunning-lock-race");
    UUID subscriptionId = UUID.randomUUID();
    Instant paidThrough = NOW.minusSeconds(60);
    jdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'MONTHLY', ?, 1)",
        subscriptionId,
        tenant.tenantId(),
        java.sql.Timestamp.from(paidThrough));
    CheckoutOperation contractSource = operation(tenant.tenantId());
    billing.createCheckout(contractSource);
    persistContractTerms(tenant.tenantId(), subscriptionId, contractSource);
    assertThat(
            dunning.markPastDue(
                new Subscription(
                    subscriptionId,
                    tenant.tenantId(),
                    "ACTIVE",
                    null,
                    null,
                    "MONTHLY",
                    paidThrough,
                    null,
                    1),
                NOW))
        .isTrue();
    isolateOtherDunningCycles(tenant.tenantId());

    AuthenticatedAdmin admin = admin(tenant.tenantId());
    when(adminAccessPolicy.isCurrent(admin)).thenReturn(true);
    when(checkoutProvider.isConfigured()).thenReturn(true);
    when(checkoutProvider.providerCode()).thenReturn(PROVIDER);
    when(checkoutProvider.providerAccountId()).thenReturn(ACCOUNT);
    when(checkoutProvider.create(any()))
        .thenReturn(
            new CheckoutSession(
                "parallel-checkout-" + tenant.tenantId(),
                "https://checkout.example.invalid/session"));
    when(offerCatalog.findApprovedOffer(PlanCode.MONTHLY))
        .thenReturn(
            java.util.Optional.of(new BillingOffer(PlanCode.MONTHLY, 1, "XTS", "terms", "offer")));

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var checkout =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS))
                  throw new IllegalStateException("Checkout race did not start");
                try {
                  startCheckout.execute(
                      admin, tenant.tenantId(), PlanCode.MONTHLY, 2, "checkout-dunning-race-key");
                  return true;
                } catch (
                    com.appagendamento.subscriptions.application
                            .SubscriptionCheckoutConflictException
                        expectedLoser) {
                  return false;
                }
              });
      var claim =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS))
                  throw new IllegalStateException("Dunning race did not start");
                return dunning.claimNextAttempt(Duration.ofMinutes(1), false);
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      boolean checkoutWon = checkout.get(20, TimeUnit.SECONDS);
      boolean dunningWon = claim.get(20, TimeUnit.SECONDS).isPresent();

      assertThat(checkoutWon).isNotEqualTo(dunningWon);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM agendamento.billing_checkout_operation WHERE tenant_id = ? AND state IN ('REQUESTED', 'SUBMITTING', 'CHECKOUT_READY', 'OUTCOME_UNKNOWN')",
                  Integer.class,
                  tenant.tenantId()))
          .isEqualTo(checkoutWon ? 1 : 0);
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND logical_sequence = 0",
                  String.class,
                  tenant.tenantId()))
          .isEqualTo(dunningWon ? "CLAIMED" : "SCHEDULED");
    }
  }

  @Test
  void dunningAuthorizationUsesPlanSnapshotAfterCurrentContractChanges() {
    Tenant tenant = tenant("immutable-dunning-plan-snapshot");
    UUID subscriptionId = UUID.randomUUID();
    Instant paidThrough = NOW.minusSeconds(60);
    jdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'MONTHLY', ?, 1)",
        subscriptionId,
        tenant.tenantId(),
        java.sql.Timestamp.from(paidThrough));
    CheckoutOperation monthly = operation(tenant.tenantId());
    billing.createCheckout(monthly);
    persistContractTerms(tenant.tenantId(), subscriptionId, monthly);
    assertThat(
            dunning.markPastDue(
                new Subscription(
                    subscriptionId,
                    tenant.tenantId(),
                    "ACTIVE",
                    null,
                    null,
                    "MONTHLY",
                    paidThrough,
                    null,
                    1),
                NOW))
        .isTrue();
    isolateOtherDunningCycles(tenant.tenantId());

    jdbc.update(
        "UPDATE agendamento.subscription SET plan_code = 'ANNUAL' WHERE tenant_id = ? AND subscription_id = ?",
        tenant.tenantId(),
        subscriptionId);
    CheckoutOperation annualRevision =
        new CheckoutOperation(
            UUID.randomUUID(),
            tenant.tenantId(),
            PlanCode.ANNUAL,
            monthly.amountMinorUnits(),
            monthly.currency(),
            "synthetic-annual-terms",
            "synthetic-annual-offer",
            1,
            "REQUESTED",
            PROVIDER,
            ACCOUNT,
            null,
            0,
            NOW.plusSeconds(1));
    billing.createCheckout(annualRevision);
    persistRevisedContractTerms(
        tenant.tenantId(), subscriptionId, annualRevision, "contract-revision-event");
    when(chargeAuthorization.isAuthorized(any(), any(), any(), any())).thenReturn(true);

    var claim = dunning.claimNextAttempt(Duration.ofMinutes(1), false).orElseThrow();
    assertThat(claim.planCode()).isEqualTo("MONTHLY");
    assertThat(dunning.authorizeAttemptSend(claim)).isTrue();
    verify(chargeAuthorization).isAuthorized(tenant.tenantId(), subscriptionId, "MONTHLY", NOW);
  }

  @Test
  void authenticatedLaterPeriodFromStartedDunningAttemptExtendsActiveSubscriptionOnce() {
    Tenant tenant = tenant("late-dunning-payment-extends-active-period");
    Instant originalPaidThrough = NOW.minusSeconds(60);
    CheckoutOperation dunningTerms = operation(tenant.tenantId());
    billing.createCheckout(dunningTerms);
    UUID sourceClaim =
        billing.claimProviderCall(tenant.tenantId(), dunningTerms.operationId()).orElseThrow();
    String sourceReference = "dunning-terms-source-" + dunningTerms.operationId();
    billing.markCheckoutReady(
        tenant.tenantId(),
        dunningTerms.operationId(),
        sourceClaim,
        PROVIDER,
        ACCOUNT,
        new CheckoutSession(sourceReference, "https://checkout.example.invalid/session"));
    assertThat(
            billing.recordAuthenticatedEvent(
                confirmed("dunning-terms-source-event", sourceReference, 1, 1)))
        .isTrue();
    UUID subscriptionId =
        jdbc.queryForObject(
            "SELECT subscription_id FROM agendamento.subscription WHERE tenant_id = ?",
            UUID.class,
            tenant.tenantId());
    jdbc.update(
        "UPDATE agendamento.subscription SET paid_through_at = ? WHERE tenant_id = ? AND subscription_id = ?",
        java.sql.Timestamp.from(originalPaidThrough),
        tenant.tenantId(),
        subscriptionId);
    assertThat(
            dunning.markPastDue(
                new Subscription(
                    subscriptionId,
                    tenant.tenantId(),
                    "ACTIVE",
                    null,
                    null,
                    "MONTHLY",
                    originalPaidThrough,
                    null,
                    1),
                NOW))
        .isTrue();
    UUID attemptId =
        jdbc.queryForObject(
            "SELECT attempt_id FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND logical_sequence = 0",
            UUID.class,
            tenant.tenantId());
    String dunningReference = "late-dunning-" + attemptId;
    jdbc.update(
        "UPDATE agendamento.billing_dunning_attempt SET state = 'ACCEPTED', ever_started = TRUE, technical_attempts = 1, provider_code = ?, provider_account_id = ?, provider_reference = ? WHERE tenant_id = ? AND attempt_id = ?",
        PROVIDER,
        ACCOUNT,
        dunningReference,
        tenant.tenantId(),
        attemptId);

    CheckoutOperation checkout = operation(tenant.tenantId());
    billing.createCheckout(checkout);
    UUID checkoutClaim =
        billing.claimProviderCall(tenant.tenantId(), checkout.operationId()).orElseThrow();
    String checkoutReference = "active-period-checkout-" + checkout.operationId();
    billing.markCheckoutReady(
        tenant.tenantId(),
        checkout.operationId(),
        checkoutClaim,
        PROVIDER,
        ACCOUNT,
        new CheckoutSession(checkoutReference, "https://checkout.example.invalid/session"));
    assertThat(
            billing.recordAuthenticatedEvent(
                confirmed("checkout-period-confirmation", checkoutReference, 1, 1)))
        .isTrue();
    Instant checkoutPeriod = NOW.plus(Duration.ofDays(30));
    assertThat(
            jdbc.queryForObject(
                    "SELECT paid_through_at FROM agendamento.subscription WHERE tenant_id = ? AND subscription_id = ?",
                    java.sql.Timestamp.class,
                    tenant.tenantId(),
                    subscriptionId)
                .toInstant())
        .isEqualTo(checkoutPeriod);

    Instant dunningPeriod = NOW.plus(Duration.ofDays(60));
    AuthenticatedPaymentEvent dunningConfirmation =
        new AuthenticatedPaymentEvent(
            PROVIDER,
            ACCOUNT,
            "late-dunning-confirmation",
            dunningReference,
            1,
            "CONFIRMED",
            1,
            "XTS",
            NOW,
            dunningPeriod);
    assertThat(billing.recordAuthenticatedEvent(dunningConfirmation)).isTrue();
    assertThat(status(tenant.tenantId())).isEqualTo("ACTIVE:4");
    assertThat(
            jdbc.queryForObject(
                    "SELECT paid_through_at FROM agendamento.subscription WHERE tenant_id = ? AND subscription_id = ?",
                    java.sql.Timestamp.class,
                    tenant.tenantId(),
                    subscriptionId)
                .toInstant())
        .isEqualTo(dunningPeriod);
    assertThat(eventDisposition("late-dunning-confirmation")).isEqualTo("APPLIED");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM agendamento.billing_dunning_attempt WHERE attempt_id = ?",
                String.class,
                attemptId))
        .isEqualTo("CONFIRMED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription_history WHERE tenant_id = ? AND subscription_id = ? AND event_type = 'PAYMENT_CONFIRMED' AND from_status = 'ACTIVE' AND to_status = 'ACTIVE'",
                Integer.class,
                tenant.tenantId(),
                subscriptionId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.outbox_event WHERE tenant_id = ? AND operation_id = ? AND event_type = 'SubscriptionPeriodExtended'",
                Integer.class,
                tenant.tenantId(),
                attemptId))
        .isEqualTo(1);
    assertThat(billing.recordAuthenticatedEvent(dunningConfirmation)).isFalse();
    assertThat(status(tenant.tenantId())).isEqualTo("ACTIVE:4");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription_history WHERE tenant_id = ? AND subscription_id = ? AND event_type = 'PAYMENT_CONFIRMED' AND from_status = 'ACTIVE' AND to_status = 'ACTIVE'",
                Integer.class,
                tenant.tenantId(),
                subscriptionId))
        .isEqualTo(1);
  }

  @Test
  void laterCheckoutEvidenceExtendsActivePeriodWithoutReplacingCurrentPlanTerms() {
    Tenant tenant = tenant("checkout-evidence-extends-active-period");
    Instant currentPeriod = NOW.plus(Duration.ofDays(10));
    CheckoutOperation annualTerms =
        new CheckoutOperation(
            UUID.randomUUID(),
            tenant.tenantId(),
            PlanCode.ANNUAL,
            1200,
            "XTS",
            "annual-terms-v1",
            "annual-offer-v1",
            0,
            "REQUESTED",
            PROVIDER,
            ACCOUNT,
            null,
            0,
            NOW);
    billing.createCheckout(annualTerms);
    UUID annualClaim =
        billing.claimProviderCall(tenant.tenantId(), annualTerms.operationId()).orElseThrow();
    String annualReference = "initial-annual-checkout-" + annualTerms.operationId();
    billing.markCheckoutReady(
        tenant.tenantId(),
        annualTerms.operationId(),
        annualClaim,
        PROVIDER,
        ACCOUNT,
        new CheckoutSession(annualReference, "https://checkout.example.invalid/annual"));
    assertThat(
            billing.recordAuthenticatedEvent(
                new AuthenticatedPaymentEvent(
                    PROVIDER,
                    ACCOUNT,
                    "initial-annual-confirmation",
                    annualReference,
                    1,
                    "CONFIRMED",
                    1200,
                    "XTS",
                    NOW,
                    currentPeriod)))
        .isTrue();

    CheckoutOperation olderMonthlyOperation = operation(tenant.tenantId());
    billing.createCheckout(olderMonthlyOperation);
    UUID claim =
        billing
            .claimProviderCall(tenant.tenantId(), olderMonthlyOperation.operationId())
            .orElseThrow();
    String reference = "late-monthly-checkout-" + olderMonthlyOperation.operationId();
    billing.markCheckoutReady(
        tenant.tenantId(),
        olderMonthlyOperation.operationId(),
        claim,
        PROVIDER,
        ACCOUNT,
        new CheckoutSession(reference, "https://checkout.example.invalid/session"));

    Instant providerPeriod = NOW.plus(Duration.ofDays(30));
    assertThat(
            billing.recordAuthenticatedEvent(
                new AuthenticatedPaymentEvent(
                    PROVIDER,
                    ACCOUNT,
                    "late-monthly-checkout-confirmation",
                    reference,
                    1,
                    "CONFIRMED",
                    1,
                    "XTS",
                    NOW,
                    providerPeriod)))
        .isTrue();

    assertThat(status(tenant.tenantId())).isEqualTo("ACTIVE:2");
    assertThat(
            jdbc.queryForObject(
                "SELECT plan_code FROM agendamento.subscription WHERE tenant_id = ?",
                String.class,
                tenant.tenantId()))
        .isEqualTo("ANNUAL");
    assertThat(
            jdbc.queryForObject(
                    "SELECT paid_through_at FROM agendamento.subscription WHERE tenant_id = ?",
                    java.sql.Timestamp.class,
                    tenant.tenantId())
                .toInstant())
        .isEqualTo(providerPeriod);
    assertThat(
            jdbc.queryForObject(
                "SELECT source_operation_id FROM agendamento.billing_subscription_contract WHERE tenant_id = ?",
                UUID.class,
                tenant.tenantId()))
        .isEqualTo(annualTerms.operationId());
    assertThat(
            jdbc.queryForObject(
                "SELECT terms_version FROM agendamento.billing_subscription_contract WHERE tenant_id = ?",
                String.class,
                tenant.tenantId()))
        .isEqualTo("annual-terms-v1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription_history WHERE tenant_id = ? AND event_type = 'PAYMENT_CONFIRMED' AND from_status = 'ACTIVE' AND to_status = 'ACTIVE' AND version = 2",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.outbox_event WHERE tenant_id = ? AND event_type = 'SubscriptionPeriodExtended' AND aggregate_version = 2",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(1);
    assertThat(
            billing.recordAuthenticatedEvent(
                new AuthenticatedPaymentEvent(
                    PROVIDER,
                    ACCOUNT,
                    "late-monthly-checkout-confirmation",
                    reference,
                    1,
                    "CONFIRMED",
                    1,
                    "XTS",
                    NOW,
                    providerPeriod)))
        .isFalse();
    assertThat(status(tenant.tenantId())).isEqualTo("ACTIVE:2");
  }

  @Test
  void expiredCheckoutEvidenceNeverCreatesReactivatesOrExtendsSubscription() {
    Tenant neverSubscribed = tenant("expired-checkout-no-subscription");
    assertExpiredCheckoutEventDoesNotGrantAccess(neverSubscribed, null, 0);

    Tenant suspended = tenant("expired-checkout-suspended");
    Instant oldPeriod = NOW.minus(Duration.ofDays(20));
    UUID suspendedId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, plan_code, paid_through_at, grace_ends_at, version) VALUES (?, ?, 'SUSPENDED', 'MONTHLY', ?, ?, 3)",
        suspendedId,
        suspended.tenantId(),
        java.sql.Timestamp.from(oldPeriod),
        java.sql.Timestamp.from(oldPeriod.plus(Duration.ofHours(168))));
    assertExpiredCheckoutEventDoesNotGrantAccess(suspended, "SUSPENDED:3", 1);

    Tenant active = tenant("expired-checkout-active");
    UUID activeId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'ANNUAL', ?, 4)",
        activeId,
        active.tenantId(),
        java.sql.Timestamp.from(oldPeriod));
    assertExpiredCheckoutEventDoesNotGrantAccess(active, "ACTIVE:4", 1);
  }

  private void assertExpiredCheckoutEventDoesNotGrantAccess(
      Tenant tenant, String previousStatus, int previousSubscriptionCount) {
    CheckoutOperation operation = operation(tenant.tenantId());
    billing.createCheckout(operation);
    UUID claim =
        billing.claimProviderCall(tenant.tenantId(), operation.operationId()).orElseThrow();
    String reference = "expired-provider-period-" + operation.operationId();
    billing.markCheckoutReady(
        tenant.tenantId(),
        operation.operationId(),
        claim,
        PROVIDER,
        ACCOUNT,
        new CheckoutSession(reference, "https://checkout.example.invalid/session"));

    assertThat(
            billing.recordAuthenticatedEvent(
                new AuthenticatedPaymentEvent(
                    PROVIDER,
                    ACCOUNT,
                    "expired-event-" + tenant.tenantId(),
                    reference,
                    1,
                    "CONFIRMED",
                    1,
                    "XTS",
                    NOW,
                    NOW)))
        .isTrue();

    assertThat(eventDisposition("expired-event-" + tenant.tenantId())).isEqualTo("EXPIRED_PERIOD");
    assertThat(statusOrNull(tenant.tenantId())).isEqualTo(previousStatus);
    assertThat(subscriptionCount(tenant.tenantId())).isEqualTo(previousSubscriptionCount);
    assertThat(historyCount(tenant.tenantId())).isZero();
    assertThat(paymentAuditCount(tenant.tenantId())).isZero();
    assertThat(outboxCount(tenant.tenantId())).isZero();
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
  void authenticatedPaymentClosesPastDueCycleAndCancelsUnstartedRetries() {
    Tenant tenant = tenant("billing-past-due-recovery");
    UUID subscriptionId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'MONTHLY', ?, 1)",
        subscriptionId,
        tenant.tenantId(),
        java.sql.Timestamp.from(NOW.minusSeconds(60)));
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();
    assertThat(status(tenant.tenantId())).isEqualTo("PAST_DUE:2");

    CheckoutOperation operation = operation(tenant.tenantId());
    billing.createCheckout(operation);
    UUID claim =
        billing.claimProviderCall(tenant.tenantId(), operation.operationId()).orElseThrow();
    String reference = "past-due-payment-" + operation.operationId();
    billing.markCheckoutReady(
        tenant.tenantId(),
        operation.operationId(),
        claim,
        PROVIDER,
        ACCOUNT,
        new CheckoutSession(reference, "https://checkout.example.invalid/session"));

    assertThat(billing.recordAuthenticatedEvent(confirmed("past-due-paid", reference, 1, 1)))
        .isTrue();
    assertThat(status(tenant.tenantId())).isEqualTo("ACTIVE:3");
    assertThat(
            jdbc.queryForObject(
                "SELECT grace_ends_at IS NULL FROM agendamento.subscription WHERE tenant_id = ?",
                Boolean.class,
                tenant.tenantId()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM agendamento.billing_dunning_cycle WHERE tenant_id = ?",
                String.class,
                tenant.tenantId()))
        .isEqualTo("PAID");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND state = 'CANCELLED'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(3);
    assertThat(billing.recordAuthenticatedEvent(confirmed("past-due-paid", reference, 1, 1)))
        .isFalse();
    assertThat(historyCount(tenant.tenantId())).isEqualTo(1);
    assertThat(paymentAuditCount(tenant.tenantId())).isEqualTo(1);
    assertThat(outboxCount(tenant.tenantId())).isEqualTo(1);
  }

  @Test
  void concurrentPaymentAndGraceSuspensionSerializeWithoutLeavingCycleOpen() throws Exception {
    Tenant tenant = tenant("billing-payment-suspension-race");
    UUID subscriptionId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'MONTHLY', ?, 1)",
        subscriptionId,
        tenant.tenantId(),
        java.sql.Timestamp.from(NOW.minusSeconds(60)));
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();
    assertThat(status(tenant.tenantId())).isEqualTo("PAST_DUE:2");
    Instant graceEnd = NOW.minusSeconds(60).plusSeconds(7 * 24 * 60 * 60L);

    CheckoutOperation operation = operation(tenant.tenantId());
    billing.createCheckout(operation);
    UUID claim =
        billing.claimProviderCall(tenant.tenantId(), operation.operationId()).orElseThrow();
    String reference = "suspension-race-" + operation.operationId();
    billing.markCheckoutReady(
        tenant.tenantId(),
        operation.operationId(),
        claim,
        PROVIDER,
        ACCOUNT,
        new CheckoutSession(reference, "https://checkout.example.invalid/session"));
    AuthenticatedPaymentEvent payment = confirmed("suspension-race-payment", reference, 1, 1);

    when(clock.instant()).thenReturn(graceEnd);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var suspension =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS))
                  throw new IllegalStateException("Largada da suspensão expirou");
                return lifecycle.materializeBillingBatch(10);
              });
      var confirmation =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS))
                  throw new IllegalStateException("Largada do pagamento expirou");
                return billing.recordAuthenticatedEvent(payment);
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      suspension.get(15, TimeUnit.SECONDS);
      assertThat(confirmation.get(15, TimeUnit.SECONDS)).isTrue();
    }

    int suspensionEvents =
        jdbc.queryForObject(
            "SELECT count(*) FROM agendamento.subscription_history WHERE tenant_id = ? AND event_type = 'PAYMENT_SUSPENDED'",
            Integer.class,
            tenant.tenantId());
    assertThat(status(tenant.tenantId())).isEqualTo("ACTIVE:" + (suspensionEvents == 1 ? 4 : 3));
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM agendamento.billing_dunning_cycle WHERE tenant_id = ?",
                String.class,
                tenant.tenantId()))
        .isEqualTo("PAID");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND state = 'CANCELLED'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(3);
    assertThat(historyCount(tenant.tenantId())).isEqualTo(1);
    assertThat(paymentAuditCount(tenant.tenantId())).isEqualTo(1);
    assertThat(outboxCount(tenant.tenantId())).isEqualTo(1);
    assertThat(suspensionEvents).isBetween(0, 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.audit_event WHERE tenant_id = ? AND action = 'SUBSCRIPTION_PAYMENT_SUSPENDED'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(suspensionEvents);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.outbox_event WHERE tenant_id = ? AND event_type = 'SubscriptionPaymentSuspended'",
                Integer.class,
                tenant.tenantId()))
        .isEqualTo(suspensionEvents);
  }

  @Test
  void paymentAndSuspensionRaceAfterSendAuthorizationPreservesInFlightAttempt() throws Exception {
    Tenant tenant = tenant("dunning-payment-send-race");
    UUID subscriptionId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'MONTHLY', ?, 1)",
        subscriptionId,
        tenant.tenantId(),
        java.sql.Timestamp.from(NOW.minusSeconds(60)));
    CheckoutOperation checkout = operation(tenant.tenantId());
    billing.createCheckout(checkout);
    persistContractTerms(tenant.tenantId(), subscriptionId, checkout);
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();
    isolateOtherDunningCycles(tenant.tenantId());
    CountDownLatch authorizationEntered = new CountDownLatch(1);
    CountDownLatch releaseAuthorization = new CountDownLatch(1);
    CountDownLatch transportEntered = new CountDownLatch(1);
    CountDownLatch releaseTransport = new CountDownLatch(1);
    AtomicReference<Instant> controlledNow = new AtomicReference<>(NOW);
    when(clock.instant()).thenAnswer(invocation -> controlledNow.get());
    doAnswer(
            invocation -> {
              authorizationEntered.countDown();
              assertThat(releaseAuthorization.await(10, TimeUnit.SECONDS)).isTrue();
              return true;
            })
        .when(chargeAuthorization)
        .isAuthorized(any(), any(), any(), any());
    when(chargeTransport.submit(any()))
        .thenAnswer(
            invocation -> {
              assertThat(
                      billing.recordAuthenticatedEvent(
                          confirmed(
                              "dunning-race-confirmed", "accepted-during-payment-race", 1, 1)))
                  .isTrue();
              transportEntered.countDown();
              assertThat(releaseTransport.await(10, TimeUnit.SECONDS)).isTrue();
              return new DunningChargeTransport.TransportResult(
                  DunningChargeTransport.Outcome.ACCEPTED,
                  PROVIDER,
                  ACCOUNT,
                  "accepted-during-payment-race",
                  null);
            });

    Instant graceEnd = NOW.minusSeconds(60).plusSeconds(7 * 24 * 60 * 60L);
    try (var executor = Executors.newFixedThreadPool(3)) {
      var worker = executor.submit(dunningWorker::processBatch);
      assertThat(authorizationEntered.await(10, TimeUnit.SECONDS)).isTrue();

      releaseAuthorization.countDown();
      assertThat(transportEntered.await(10, TimeUnit.SECONDS)).isTrue();
      controlledNow.set(graceEnd);
      CountDownLatch suspensionReady = new CountDownLatch(1);
      var suspension =
          executor.submit(
              () -> {
                suspensionReady.countDown();
                return lifecycle.materializeBillingBatch(10);
              });
      assertThat(suspensionReady.await(5, TimeUnit.SECONDS)).isTrue();
      suspension.get(15, TimeUnit.SECONDS);
      releaseTransport.countDown();
      assertThat(worker.get(15, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      releaseAuthorization.countDown();
      releaseTransport.countDown();
    }

    assertThat(status(tenant.tenantId())).startsWith("ACTIVE:");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM agendamento.billing_dunning_cycle WHERE tenant_id = ?",
                String.class,
                tenant.tenantId()))
        .isEqualTo("PAID");
    assertThat(
            jdbc.queryForObject(
                "SELECT state = 'CONFIRMED' AND provider_reference = 'accepted-during-payment-race' AND ever_started FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND logical_sequence = 0",
                Boolean.class,
                tenant.tenantId()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND logical_sequence > 0 AND state <> 'CANCELLED'",
                Integer.class,
                tenant.tenantId()))
        .isZero();
    assertThat(historyCount(tenant.tenantId())).isEqualTo(1);
    assertThat(paymentAuditCount(tenant.tenantId())).isEqualTo(1);
    assertThat(outboxCount(tenant.tenantId())).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription_history WHERE tenant_id = ? AND event_type = 'PAYMENT_SUSPENDED'",
                Integer.class,
                tenant.tenantId()))
        .isBetween(0, 1);
    org.mockito.Mockito.verify(chargeTransport).submit(any());
  }

  @Test
  void authorizationCompletingAtGraceEndDoesNotStartACharge() throws Exception {
    Tenant tenant = tenant("dunning-authorization-at-grace-end");
    UUID subscriptionId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'MONTHLY', ?, 1)",
        subscriptionId,
        tenant.tenantId(),
        java.sql.Timestamp.from(NOW.minusSeconds(60)));
    CheckoutOperation checkout = operation(tenant.tenantId());
    billing.createCheckout(checkout);
    persistContractTerms(tenant.tenantId(), subscriptionId, checkout);
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();
    isolateOtherDunningCycles(tenant.tenantId());

    CountDownLatch authorizationEntered = new CountDownLatch(1);
    CountDownLatch releaseAuthorization = new CountDownLatch(1);
    AtomicReference<Instant> controlledNow = new AtomicReference<>(NOW);
    when(clock.instant()).thenAnswer(invocation -> controlledNow.get());
    doAnswer(
            invocation -> {
              authorizationEntered.countDown();
              assertThat(releaseAuthorization.await(10, TimeUnit.SECONDS)).isTrue();
              return true;
            })
        .when(chargeAuthorization)
        .isAuthorized(any(), any(), any(), any());

    Instant graceEnd = NOW.minusSeconds(60).plusSeconds(7 * 24 * 60 * 60L);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var worker = executor.submit(dunningWorker::processBatch);
      assertThat(authorizationEntered.await(10, TimeUnit.SECONDS)).isTrue();
      controlledNow.set(graceEnd);
      releaseAuthorization.countDown();
      assertThat(worker.get(15, TimeUnit.SECONDS)).isZero();
    } finally {
      releaseAuthorization.countDown();
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT state = 'CANCELLED' AND ever_started = FALSE AND technical_attempts = 0 AND last_error_code = 'DUNNING_CYCLE_NOT_ELIGIBLE' FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND logical_sequence = 0",
                Boolean.class,
                tenant.tenantId()))
        .isTrue();
    org.mockito.Mockito.verify(chargeTransport, org.mockito.Mockito.never()).submit(any());
  }

  @Test
  void authorizationOneMicrosecondBeforeGraceEndStartsTheLogicalAttempt() {
    Tenant tenant = tenant("dunning-authorization-before-grace-end");
    UUID subscriptionId = UUID.randomUUID();
    Instant paidThrough = NOW.minusSeconds(60);
    jdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'MONTHLY', ?, 1)",
        subscriptionId,
        tenant.tenantId(),
        java.sql.Timestamp.from(paidThrough));
    CheckoutOperation contractOperation = operation(tenant.tenantId());
    billing.createCheckout(contractOperation);
    persistContractTerms(tenant.tenantId(), subscriptionId, contractOperation);
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();
    isolateOtherDunningCycles(tenant.tenantId());

    Instant graceEnd = paidThrough.plusSeconds(168 * 60 * 60L);
    Instant justBeforeGraceEnd = graceEnd.minusNanos(1_000);
    when(clock.instant()).thenReturn(justBeforeGraceEnd);
    when(chargeAuthorization.isAuthorized(any(), any(), any(), any())).thenReturn(true);
    when(chargeTransport.submit(any()))
        .thenReturn(
            new DunningChargeTransport.TransportResult(
                DunningChargeTransport.Outcome.ACCEPTED,
                PROVIDER,
                ACCOUNT,
                "accepted-before-grace-end",
                null));

    assertThat(dunningWorker.processBatch()).isEqualTo(1);
    String acceptedState =
        jdbc.queryForObject(
            "SELECT state || '|' || ever_started || '|' || technical_attempts || '|' || COALESCE(provider_reference, '') || '|' || COALESCE(last_error_code, '') FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND logical_sequence = 6",
            String.class,
            tenant.tenantId());
    assertThat(acceptedState)
        .as("attempt state immediately before grace end: %s", acceptedState)
        .isEqualTo("ACCEPTED|true|1|accepted-before-grace-end|");
    org.mockito.Mockito.verify(chargeAuthorization)
        .isAuthorized(
            org.mockito.ArgumentMatchers.eq(tenant.tenantId()),
            org.mockito.ArgumentMatchers.eq(subscriptionId),
            org.mockito.ArgumentMatchers.eq("MONTHLY"),
            org.mockito.ArgumentMatchers.eq(justBeforeGraceEnd));
    org.mockito.Mockito.verify(chargeTransport).submit(any());
  }

  @Test
  void authorizationWaitingForTenantLockRechecksClockAfterGraceEnd() throws Exception {
    Tenant tenant = tenant("dunning-authorization-lock-crosses-grace-end");
    UUID subscriptionId = UUID.randomUUID();
    Instant paidThrough = NOW.minusSeconds(60);
    jdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'MONTHLY', ?, 1)",
        subscriptionId,
        tenant.tenantId(),
        java.sql.Timestamp.from(paidThrough));
    Instant graceEnd = paidThrough.plusSeconds(168 * 60 * 60L);
    CheckoutOperation contractOperation = operation(tenant.tenantId());
    billing.createCheckout(contractOperation);
    persistContractTerms(tenant.tenantId(), subscriptionId, contractOperation);
    assertThat(lifecycle.allowsNewPublicAppointments(tenant.tenantId())).isTrue();
    isolateOtherDunningCycles(tenant.tenantId());
    AtomicReference<Instant> controlledNow = new AtomicReference<>(graceEnd.minusSeconds(5));
    when(clock.instant()).thenAnswer(invocation -> controlledNow.get());
    when(chargeAuthorization.isAuthorized(any(), any(), any(), any())).thenReturn(true);
    var claim = dunning.claimNextAttempt(java.time.Duration.ofSeconds(60), false).orElseThrow();

    try (var executor = Executors.newSingleThreadExecutor();
        var lockConnection = PostgresTestEnvironment.migrationDataSource().getConnection()) {
      lockConnection.setAutoCommit(false);
      try (var lock =
          lockConnection.prepareStatement(
              "SELECT tenant_id FROM agendamento.tenant WHERE tenant_id = ? FOR UPDATE")) {
        lock.setObject(1, tenant.tenantId());
        try (var result = lock.executeQuery()) {
          assertThat(result.next()).isTrue();
        }
      }

      var authorization = executor.submit(() -> dunning.authorizeAttemptSend(claim));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      boolean waitingOnTenantLock = false;
      while (System.nanoTime() < deadline) {
        Boolean waiting =
            new JdbcTemplate(PostgresTestEnvironment.migrationDataSource())
                .queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE '%SELECT tenant_id FROM agendamento.tenant%' AND query ILIKE '%FOR UPDATE%')",
                    Boolean.class);
        if (Boolean.TRUE.equals(waiting)) {
          waitingOnTenantLock = true;
          break;
        }
        Thread.yield();
      }
      assertThat(waitingOnTenantLock)
          .as("authorization waits for the tenant lock before reading Clock")
          .isTrue();

      controlledNow.set(graceEnd);
      lockConnection.commit();

      assertThat(authorization.get(15, TimeUnit.SECONDS)).isFalse();
    } finally {
      org.mockito.Mockito.verify(chargeAuthorization, org.mockito.Mockito.never())
          .isAuthorized(any(), any(), any(), any());
      org.mockito.Mockito.verify(chargeTransport, org.mockito.Mockito.never()).submit(any());
    }

    String lockedAttemptState =
        jdbc.queryForObject(
            "SELECT state || '|' || ever_started || '|' || technical_attempts || '|' || COALESCE(last_error_code, '') FROM agendamento.billing_dunning_attempt WHERE tenant_id = ? AND logical_sequence = 6",
            String.class,
            tenant.tenantId());
    assertThat(lockedAttemptState)
        .as(
            "attempt state after eligibility expires while waiting for lock: %s",
            lockedAttemptState)
        .isEqualTo("CANCELLED|false|0|DUNNING_CYCLE_NOT_ELIGIBLE");
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

  private void persistContractTerms(
      UUID tenantId, UUID subscriptionId, CheckoutOperation sourceOperation) {
    // Models a confirmed checkout using a synthetic linked inbox fact without leaving
    // a live checkout that would block a parallel logical dunning charge.
    String reference = "confirmed-terms-" + sourceOperation.operationId();
    jdbc.update(
        "UPDATE agendamento.billing_checkout_operation SET state = 'CONFIRMED', provider_reference = ?, last_provider_sequence = 1, updated_at = ? WHERE tenant_id = ? AND operation_id = ?",
        reference,
        java.sql.Timestamp.from(NOW.minusSeconds(2)),
        tenantId,
        sourceOperation.operationId());
    jdbc.update(
        "INSERT INTO agendamento.billing_event_inbox (provider_code, provider_account_id, provider_event_id, provider_reference, provider_sequence, outcome, amount_minor_units, currency, occurred_at, paid_through_at, tenant_id, operation_id, disposition) VALUES (?, ?, ?, ?, 1, 'CONFIRMED', ?, ?, ?, ?, ?, ?, 'PENDING')",
        PROVIDER,
        ACCOUNT,
        "confirmed-terms-event-" + sourceOperation.operationId(),
        reference,
        sourceOperation.amountMinorUnits(),
        sourceOperation.currency(),
        java.sql.Timestamp.from(NOW.minusSeconds(2)),
        java.sql.Timestamp.from(NOW.plus(Duration.ofDays(30))),
        tenantId,
        sourceOperation.operationId());
    jdbc.update(
        """
        INSERT INTO agendamento.billing_subscription_contract
          (subscription_id, tenant_id, source_operation_id, amount_minor_units, currency,
           terms_version, offer_version, plan_code, recorded_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        subscriptionId,
        tenantId,
        sourceOperation.operationId(),
        sourceOperation.amountMinorUnits(),
        sourceOperation.currency(),
        sourceOperation.termsVersion(),
        sourceOperation.offerVersion(),
        sourceOperation.planCode().name(),
        java.sql.Timestamp.from(NOW.minusSeconds(1)));
    jdbc.update(
        "UPDATE agendamento.billing_event_inbox SET disposition = 'APPLIED' WHERE provider_code = ? AND provider_account_id = ? AND provider_event_id = ?",
        PROVIDER,
        ACCOUNT,
        "confirmed-terms-event-" + sourceOperation.operationId());
  }

  private void persistRevisedContractTerms(
      UUID tenantId, UUID subscriptionId, CheckoutOperation sourceOperation, String eventId) {
    // The new terms are linked to a separate synthetic confirmed checkout fact;
    // the pre-existing Dunning attempt must continue to use its immutable snapshot.
    String reference = "confirmed-revised-terms-" + sourceOperation.operationId();
    jdbc.update(
        "UPDATE agendamento.billing_checkout_operation SET state = 'CONFIRMED', provider_reference = ?, last_provider_sequence = 2, updated_at = ? WHERE tenant_id = ? AND operation_id = ?",
        reference,
        java.sql.Timestamp.from(NOW.plusSeconds(2)),
        tenantId,
        sourceOperation.operationId());
    jdbc.update(
        "INSERT INTO agendamento.billing_event_inbox (provider_code, provider_account_id, provider_event_id, provider_reference, provider_sequence, outcome, amount_minor_units, currency, occurred_at, paid_through_at, tenant_id, operation_id, disposition) VALUES (?, ?, ?, ?, 2, 'CONFIRMED', ?, ?, ?, ?, ?, ?, 'PENDING')",
        PROVIDER,
        ACCOUNT,
        eventId,
        reference,
        sourceOperation.amountMinorUnits(),
        sourceOperation.currency(),
        java.sql.Timestamp.from(NOW.plusSeconds(1)),
        java.sql.Timestamp.from(NOW.plus(Duration.ofDays(60))),
        tenantId,
        sourceOperation.operationId());
    jdbc.update(
        "UPDATE agendamento.billing_subscription_contract SET source_operation_id = ?, amount_minor_units = ?, currency = ?, terms_version = ?, offer_version = ?, plan_code = ?, recorded_at = ? WHERE tenant_id = ? AND subscription_id = ?",
        sourceOperation.operationId(),
        sourceOperation.amountMinorUnits(),
        sourceOperation.currency(),
        sourceOperation.termsVersion(),
        sourceOperation.offerVersion(),
        sourceOperation.planCode().name(),
        java.sql.Timestamp.from(NOW.plusSeconds(3)),
        tenantId,
        subscriptionId);
    jdbc.update(
        "UPDATE agendamento.billing_event_inbox SET disposition = 'APPLIED' WHERE provider_code = ? AND provider_account_id = ? AND provider_event_id = ?",
        PROVIDER,
        ACCOUNT,
        eventId);
  }

  private void isolateOtherDunningCycles(UUID targetTenantId) {
    jdbc.update(
        "UPDATE agendamento.billing_dunning_attempt SET state = 'CANCELLED', claim_token = NULL, lease_until = NULL, next_attempt_at = NULL, skip_reason = NULL, updated_at = ? WHERE tenant_id <> ? AND state IN ('SCHEDULED', 'BLOCKED_CONFIGURATION', 'CLAIMED', 'SUBMITTING', 'RETRYABLE_NO_EFFECT', 'OUTCOME_UNKNOWN', 'RECONCILING')",
        java.sql.Timestamp.from(NOW),
        targetTenantId);
    jdbc.update(
        "UPDATE agendamento.billing_dunning_cycle SET state = 'SUSPENDED', closed_at = ? WHERE tenant_id <> ? AND state = 'OPEN'",
        java.sql.Timestamp.from(NOW),
        targetTenantId);
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

  private String statusOrNull(UUID tenantId) {
    return jdbc
        .queryForList(
            "SELECT status || ':' || version FROM agendamento.subscription WHERE tenant_id = ?",
            String.class,
            tenantId)
        .stream()
        .findFirst()
        .orElse(null);
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
