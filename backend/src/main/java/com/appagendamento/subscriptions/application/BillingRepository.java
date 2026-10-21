package com.appagendamento.subscriptions.application;

import com.appagendamento.subscriptions.api.AuthenticatedPaymentEvent;
import com.appagendamento.subscriptions.api.HostedCheckoutProvider.CheckoutSession;
import com.appagendamento.subscriptions.domain.CheckoutOperation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingRepository {

  void createCheckout(CheckoutOperation operation);

  Optional<CheckoutOperation> findCheckout(UUID tenantId, UUID operationId);

  Optional<UUID> claimProviderCall(UUID tenantId, UUID operationId);

  void markCheckoutUnknown(UUID tenantId, UUID operationId, UUID claimToken);

  void markCheckoutReady(
      UUID tenantId,
      UUID operationId,
      UUID claimToken,
      String providerCode,
      String providerAccountId,
      CheckoutSession session);

  void reconcileAcceptedCheckout(
      UUID tenantId,
      UUID operationId,
      String providerCode,
      String providerAccountId,
      CheckoutSession session);

  Optional<CheckoutOperation> findCheckoutByProviderReference(
      String providerCode, String providerAccountId, String providerReference);

  boolean recordAuthenticatedEvent(AuthenticatedPaymentEvent event);

  List<CheckoutOperation> findOpenCheckouts(UUID tenantId);
}
