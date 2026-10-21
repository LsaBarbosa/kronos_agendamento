package com.appagendamento.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.appagendamento.subscriptions.domain.PlanCatalog;
import com.appagendamento.subscriptions.domain.PlanCode;
import org.junit.jupiter.api.Test;

class PlanCatalogTest {

  @Test
  void presentsMonthlyAndAnnualWithoutInventingPriceOrCurrency() {
    var plans = PlanCatalog.plans();

    assertThat(plans).hasSize(2);
    assertThat(plans)
        .extracting(plan -> plan.code())
        .containsExactly(PlanCode.MONTHLY, PlanCode.ANNUAL);
    assertThat(plans.get(0).recurring()).isTrue();
    assertThat(plans.get(0).annualEquivalentMonthlyPayments()).isNull();
    assertThat(plans.get(1).recurring()).isFalse();
    assertThat(plans.get(1).annualEquivalentMonthlyPayments()).isEqualTo(10);
  }
}
