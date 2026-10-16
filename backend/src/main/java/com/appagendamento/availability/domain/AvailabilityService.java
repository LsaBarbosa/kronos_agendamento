package com.appagendamento.availability.domain;

import java.util.UUID;

public record AvailabilityService(
    UUID serviceId, UUID tenantId, boolean active, int durationMinutes, int bufferAfterMinutes) {

  public AvailabilityService {
    if (serviceId == null
        || tenantId == null
        || durationMinutes <= 0
        || durationMinutes % 15 != 0
        || bufferAfterMinutes < 0
        || bufferAfterMinutes % 15 != 0) {
      throw new ScheduleValidationException("Dados do serviço para disponibilidade inválidos");
    }
  }
}
