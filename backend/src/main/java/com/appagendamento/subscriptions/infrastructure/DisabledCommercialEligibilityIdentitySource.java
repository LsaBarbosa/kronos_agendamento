package com.appagendamento.subscriptions.infrastructure;

import com.appagendamento.subscriptions.api.CommercialEligibilityIdentitySource;
import com.appagendamento.subscriptions.api.VerifiedCommercialIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!e2e")
public class DisabledCommercialEligibilityIdentitySource
    implements CommercialEligibilityIdentitySource {

  @Override
  public Optional<VerifiedCommercialIdentity> findVerifiedIdentity(UUID tenantId) {
    return Optional.empty();
  }
}
