package com.appagendamento.subscriptions.api;

import java.util.Optional;
import java.util.UUID;

public interface CommercialEligibilityIdentitySource {

  Optional<VerifiedCommercialIdentity> findVerifiedIdentity(UUID tenantId);
}
