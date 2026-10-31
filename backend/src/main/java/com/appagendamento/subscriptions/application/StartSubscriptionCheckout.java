package com.appagendamento.subscriptions.application;

import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.application.IdempotencyKey;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.subscriptions.api.BillingOffer;
import com.appagendamento.subscriptions.api.BillingOfferCatalog;
import com.appagendamento.subscriptions.api.HostedCheckoutProvider;
import com.appagendamento.subscriptions.api.PaymentIntegrationUnavailableException;
import com.appagendamento.subscriptions.domain.CheckoutOperation;
import com.appagendamento.subscriptions.domain.CheckoutReceipt;
import com.appagendamento.subscriptions.domain.CheckoutView;
import com.appagendamento.subscriptions.domain.PlanCode;
import com.appagendamento.subscriptions.domain.Subscription;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class StartSubscriptionCheckout {

  private static final String SCOPE = "subscriptions.checkout.v1";
  private final AdminAccessPolicy access;
  private final SubscriptionRepository subscriptions;
  private final SubscriptionDunningRepository dunning;
  private final BillingRepository billing;
  private final BillingOfferCatalog offers;
  private final HostedCheckoutProvider provider;
  private final IdempotencyCoordinator idempotency;
  private final CheckoutReceiptCodec codec;
  private final TenantAgendaLock tenantLock;
  private final Clock clock;

  public StartSubscriptionCheckout(
      AdminAccessPolicy access,
      SubscriptionRepository subscriptions,
      SubscriptionDunningRepository dunning,
      BillingRepository billing,
      BillingOfferCatalog offers,
      HostedCheckoutProvider provider,
      IdempotencyCoordinator idempotency,
      CheckoutReceiptCodec codec,
      TenantAgendaLock tenantLock,
      Clock clock) {
    this.access = access;
    this.subscriptions = subscriptions;
    this.dunning = dunning;
    this.billing = billing;
    this.offers = offers;
    this.provider = provider;
    this.idempotency = idempotency;
    this.codec = codec;
    this.tenantLock = tenantLock;
    this.clock = clock;
  }

  public CheckoutView execute(
      AuthenticatedAdmin admin,
      UUID expectedTenantContextId,
      PlanCode planCode,
      long expectedVersion,
      String key) {
    validateAdmin(admin);
    validateTenantContext(admin, expectedTenantContextId);
    if (planCode == null || expectedVersion < 0) {
      throw new IllegalArgumentException("Comando de checkout inválido");
    }
    String digest =
        IdempotencyKey.sha256(
            ("subscription.checkout.v1\n" + planCode.name() + "\n" + expectedVersion)
                .getBytes(StandardCharsets.UTF_8));
    var command =
        idempotency.execute(
            admin.tenantId(),
            SCOPE,
            key,
            digest,
            () -> {
              validateAdmin(admin);
              validateTenantContext(admin, expectedTenantContextId);
            },
            operationId -> {
              if (!provider.isConfigured()) throw new PaymentIntegrationUnavailableException();
              BillingOffer offer =
                  offers
                      .findApprovedOffer(planCode)
                      .orElseThrow(PaymentIntegrationUnavailableException::new);
              tenantLock.acquire(admin.tenantId());
              Optional<Subscription> current = subscriptions.findCurrent(admin.tenantId());
              long currentVersion = current.map(Subscription::version).orElse(0L);
              if (currentVersion != expectedVersion) {
                throw new SubscriptionCheckoutConflictException("VERSION_CONFLICT");
              }
              if (current.map(value -> value.status().equals("ACTIVE")).orElse(false)) {
                throw new SubscriptionCheckoutConflictException("SUBSCRIPTION_ALREADY_ACTIVE");
              }
              if (current
                  .filter(
                      value ->
                          dunning.hasUnresolvedPayment(admin.tenantId(), value.subscriptionId()))
                  .isPresent()) {
                throw new SubscriptionCheckoutConflictException(
                    "DUNNING_PAYMENT_OUTCOME_UNRESOLVED");
              }
              billing.createCheckout(
                  new CheckoutOperation(
                      operationId,
                      admin.tenantId(),
                      planCode,
                      offer.amountMinorUnits(),
                      offer.currency(),
                      offer.termsVersion(),
                      offer.offerVersion(),
                      expectedVersion,
                      "REQUESTED",
                      provider.providerCode(),
                      provider.providerAccountId(),
                      null,
                      0,
                      clock.instant()));
              return new CheckoutReceipt(operationId);
            },
            codec);
    CheckoutOperation operation =
        billing
            .findCheckout(admin.tenantId(), command.value().operationId())
            .orElseThrow(() -> new IllegalStateException("Operação financeira não encontrada"));
    return resume(admin.tenantId(), operation, command.replayed());
  }

  public CheckoutView recover(AuthenticatedAdmin admin, UUID operationId) {
    validateAdmin(admin);
    CheckoutOperation operation =
        billing
            .findCheckout(admin.tenantId(), operationId)
            .orElseThrow(() -> new SubscriptionCheckoutConflictException("CHECKOUT_NOT_FOUND"));
    return resume(admin.tenantId(), operation, true);
  }

  private CheckoutView resume(UUID tenantId, CheckoutOperation operation, boolean replayed) {
    if (operation.state().equals("REQUESTED")) {
      Optional<UUID> claim = billing.claimProviderCall(tenantId, operation.operationId());
      if (claim.isPresent()) {
        try {
          var session =
              provider.create(
                  new HostedCheckoutProvider.CheckoutCommand(
                      operation.operationId(),
                      tenantId,
                      new BillingOffer(
                          operation.planCode(),
                          operation.amountMinorUnits(),
                          operation.currency(),
                          operation.termsVersion(),
                          operation.offerVersion()),
                      operation.operationId().toString()));
          billing.markCheckoutReady(
              tenantId,
              operation.operationId(),
              claim.get(),
              operation.providerCode(),
              operation.providerAccountId(),
              session);
          CheckoutOperation current =
              billing.findCheckout(tenantId, operation.operationId()).orElse(operation);
          return view(
              current,
              current.state(),
              current.state().equals("CHECKOUT_READY") ? session.redirectUrl() : null,
              replayed);
        } catch (RuntimeException uncertain) {
          billing.markCheckoutUnknown(tenantId, operation.operationId(), claim.get());
          return view(operation, "OUTCOME_UNKNOWN", null, replayed);
        }
      }
    }
    if (provider.isConfigured()
        && (operation.state().equals("SUBMITTING")
            || operation.state().equals("OUTCOME_UNKNOWN")
            || operation.state().equals("CHECKOUT_READY"))) {
      try {
        var recovered = provider.findByOperationId(operation.operationId());
        if (recovered.isPresent()) {
          billing.reconcileAcceptedCheckout(
              tenantId,
              operation.operationId(),
              operation.providerCode(),
              operation.providerAccountId(),
              recovered.get());
          CheckoutOperation current =
              billing.findCheckout(tenantId, operation.operationId()).orElse(operation);
          return view(
              current,
              current.state(),
              current.state().equals("CHECKOUT_READY") ? recovered.get().redirectUrl() : null,
              replayed);
        }
      } catch (RuntimeException ignored) {
      }
    }
    String state = operation.state().equals("SUBMITTING") ? "OUTCOME_UNKNOWN" : operation.state();
    return view(operation, state, null, replayed);
  }

  private static CheckoutView view(
      CheckoutOperation operation, String state, String redirectUrl, boolean replayed) {
    return new CheckoutView(
        operation.operationId(),
        operation.planCode(),
        state,
        operation.amountMinorUnits(),
        operation.currency(),
        operation.termsVersion(),
        operation.createdAt(),
        redirectUrl,
        replayed);
  }

  private void validateAdmin(AuthenticatedAdmin admin) {
    if (admin == null || !"TENANT_ADMIN".equals(admin.role()) || !access.isCurrent(admin)) {
      throw new SubscriptionAccessDeniedException();
    }
  }

  private static void validateTenantContext(
      AuthenticatedAdmin admin, UUID expectedTenantContextId) {
    if (expectedTenantContextId == null || !admin.tenantId().equals(expectedTenantContextId)) {
      throw new SubscriptionCheckoutConflictException("TENANT_CONTEXT_CHANGED");
    }
  }
}
