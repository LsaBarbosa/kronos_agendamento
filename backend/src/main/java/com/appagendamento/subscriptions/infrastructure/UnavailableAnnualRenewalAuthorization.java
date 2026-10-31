package com.appagendamento.subscriptions.infrastructure;

import com.appagendamento.subscriptions.application.AnnualRenewalAuthorization;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UnavailableAnnualRenewalAuthorization implements AnnualRenewalAuthorization {

  @Override
  public boolean isAuthorizedForPeriod(
      UUID tenantId, UUID subscriptionId, Instant periodEndsAt, Instant asOf) {
    return false;
  }
}
