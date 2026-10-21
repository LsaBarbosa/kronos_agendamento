package com.appagendamento.subscriptions.api;

import java.util.UUID;

public interface PublicSubscriptionEligibility {

  boolean allowsNewPublicAppointments(UUID tenantId);
}
