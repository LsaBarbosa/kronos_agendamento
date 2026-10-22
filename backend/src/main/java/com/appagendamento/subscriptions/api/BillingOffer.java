package com.appagendamento.subscriptions.api;

import com.appagendamento.subscriptions.domain.PlanCode;

public record BillingOffer(
    PlanCode planCode,
    long amountMinorUnits,
    String currency,
    String termsVersion,
    String offerVersion) {

  public BillingOffer {
    if (planCode == null
        || amountMinorUnits <= 0
        || currency == null
        || !currency.matches("[A-Z]{3}")
        || termsVersion == null
        || termsVersion.isBlank()
        || offerVersion == null
        || offerVersion.isBlank()) {
      throw new IllegalArgumentException("Oferta financeira inválida");
    }
  }
}
