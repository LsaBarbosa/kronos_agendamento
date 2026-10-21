package com.appagendamento.subscriptions.application;

import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.subscriptions.api.HostedCheckoutProvider;
import com.appagendamento.subscriptions.domain.CheckoutOperation;
import com.appagendamento.subscriptions.domain.CheckoutView;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GetAdminCheckoutOperation {

  private final AdminAccessPolicy access;
  private final BillingRepository billing;
  private final HostedCheckoutProvider provider;

  public GetAdminCheckoutOperation(
      AdminAccessPolicy access, BillingRepository billing, HostedCheckoutProvider provider) {
    this.access = access;
    this.billing = billing;
    this.provider = provider;
  }

  public CheckoutView execute(AuthenticatedAdmin admin, UUID operationId) {
    if (admin == null || !"TENANT_ADMIN".equals(admin.role()) || !access.isCurrent(admin)) {
      throw new SubscriptionAccessDeniedException();
    }
    CheckoutOperation operation =
        billing
            .findCheckout(admin.tenantId(), operationId)
            .orElseThrow(() -> new SubscriptionCheckoutConflictException("CHECKOUT_NOT_FOUND"));
    if (provider.isConfigured()
        && java.util.Set.of("SUBMITTING", "OUTCOME_UNKNOWN", "CHECKOUT_READY")
            .contains(operation.state())) {
      try {
        provider
            .findByOperationId(operation.operationId())
            .ifPresent(
                session -> {
                  billing.reconcileAcceptedCheckout(
                      admin.tenantId(),
                      operation.operationId(),
                      operation.providerCode(),
                      operation.providerAccountId(),
                      session);
                });
      } catch (RuntimeException ignored) {
      }
    }
    CheckoutOperation current =
        billing.findCheckout(admin.tenantId(), operationId).orElse(operation);
    return new CheckoutView(
        current.operationId(),
        current.planCode(),
        current.state(),
        current.amountMinorUnits(),
        current.currency(),
        current.termsVersion(),
        current.createdAt(),
        null,
        true);
  }
}
