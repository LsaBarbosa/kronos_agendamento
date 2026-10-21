package com.appagendamento.subscriptions.domain;

import java.util.List;

public final class PlanCatalog {

  private static final List<SubscriptionPlan> PLANS =
      List.of(
          new SubscriptionPlan(PlanCode.MONTHLY, "MONTHLY", true, null),
          new SubscriptionPlan(PlanCode.ANNUAL, "ANNUAL", false, 10));

  private PlanCatalog() {}

  public static List<SubscriptionPlan> plans() {
    return PLANS;
  }
}
