package com.appagendamento.subscriptions.api;

import java.time.Instant;

public record AuthenticatedPaymentEvent(
    String providerCode,
    String providerAccountId,
    String providerEventId,
    String providerReference,
    long providerSequence,
    String outcome,
    long amountMinorUnits,
    String currency,
    Instant occurredAt,
    Instant paidThroughAt) {

  public AuthenticatedPaymentEvent {
    if (providerCode == null
        || providerAccountId == null
        || providerEventId == null
        || providerEventId.isBlank()
        || providerReference == null
        || providerReference.isBlank()
        || providerSequence < 1
        || outcome == null
        || !java.util.Set.of("PENDING", "CONFIRMED", "DECLINED").contains(outcome)
        || amountMinorUnits <= 0
        || currency == null
        || !currency.matches("[A-Z]{3}")
        || occurredAt == null
        || (outcome.equals("CONFIRMED") && paidThroughAt == null)) {
      throw new IllegalArgumentException("Evento financeiro autenticado inválido");
    }
  }
}
