package com.appagendamento.clients.api;

import java.util.UUID;

public interface ClientBookingRestrictionQuery {

  ClientBookingRestrictionStatus status(UUID tenantId, UUID clientId);
}
