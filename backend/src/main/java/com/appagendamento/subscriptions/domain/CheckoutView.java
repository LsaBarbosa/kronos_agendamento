package com.appagendamento.subscriptions.domain;

import java.time.Instant;
import java.util.UUID;

public record CheckoutView(
    UUID operationId,
    PlanCode planCode,
    String state,
    long amountMinorUnits,
    String currency,
    String termsVersion,
    Instant createdAt,
    String redirectUrl,
    boolean replayed) {}
