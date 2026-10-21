package com.appagendamento.subscriptions.domain;

import java.time.Instant;
import java.util.UUID;

public record CheckoutOperation(
    UUID operationId,
    UUID tenantId,
    PlanCode planCode,
    long amountMinorUnits,
    String currency,
    String termsVersion,
    String offerVersion,
    long expectedSubscriptionVersion,
    String state,
    String providerCode,
    String providerAccountId,
    String providerReference,
    long lastProviderSequence,
    Instant createdAt) {}
