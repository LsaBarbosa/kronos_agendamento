package com.appagendamento.catalog.api;

import java.util.UUID;

public record ServiceAvailabilityView(
    UUID serviceId,
    UUID tenantId,
    String name,
    boolean active,
    int durationMinutes,
    int bufferAfterMinutes) {

  public ServiceAvailabilityView(
      UUID serviceId, UUID tenantId, boolean active, int durationMinutes, int bufferAfterMinutes) {
    this(serviceId, tenantId, null, active, durationMinutes, bufferAfterMinutes);
  }
}
