package com.appagendamento.subscriptions.api;

import java.util.Optional;
import java.util.UUID;

public interface HostedCheckoutProvider {

  default boolean isConfigured() {
    return true;
  }

  String providerCode();

  String providerAccountId();

  CheckoutSession create(CheckoutCommand command);

  Optional<CheckoutSession> findByOperationId(UUID operationId);

  record CheckoutCommand(
      UUID operationId, UUID tenantId, BillingOffer offer, String externalIdempotencyKey) {}

  record CheckoutSession(String providerReference, String redirectUrl) {
    public CheckoutSession {
      if (providerReference == null || providerReference.isBlank()) {
        throw new IllegalArgumentException("Referência financeira ausente");
      }
      java.net.URI uri = java.net.URI.create(redirectUrl == null ? "" : redirectUrl);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
        throw new IllegalArgumentException("URL de checkout hospedado inválida");
      }
    }
  }
}
