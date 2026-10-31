package com.appagendamento.subscriptions.infrastructure;

import com.appagendamento.subscriptions.application.DunningChargeAuthorization;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UnavailableDunningChargeAuthorization implements DunningChargeAuthorization {

  @Override
  public boolean isAuthorized(UUID tenantId, UUID subscriptionId, String planCode, Instant asOf) {
    return false;
  }
}
