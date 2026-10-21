package com.appagendamento.subscriptions.domain;

public record SubscriptionPlan(
    PlanCode code,
    String billingFrequency,
    boolean recurring,
    Integer annualEquivalentMonthlyPayments) {

  public SubscriptionPlan {
    if (code == null || billingFrequency == null) {
      throw new IllegalArgumentException("Plano inválido");
    }
    if (code == PlanCode.ANNUAL && !Integer.valueOf(10).equals(annualEquivalentMonthlyPayments)) {
      throw new IllegalArgumentException("Equivalência anual inválida");
    }
    if (code == PlanCode.MONTHLY && annualEquivalentMonthlyPayments != null) {
      throw new IllegalArgumentException("Plano mensal não possui equivalência anual");
    }
  }
}
