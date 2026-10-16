package com.appagendamento.catalog.api;

import java.util.Optional;
import java.util.UUID;

public interface ServiceAvailabilityQuery {

  Optional<ServiceAvailabilityView> findForAvailability(UUID tenantId, UUID serviceId);
}
