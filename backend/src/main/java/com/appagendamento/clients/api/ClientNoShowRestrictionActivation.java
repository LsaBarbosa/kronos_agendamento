package com.appagendamento.clients.api;

import java.time.Instant;
import java.util.UUID;

public interface ClientNoShowRestrictionActivation {

  void evaluateAfterNoShow(UUID tenantId, UUID clientId, UUID appointmentId, Instant asOf);
}
