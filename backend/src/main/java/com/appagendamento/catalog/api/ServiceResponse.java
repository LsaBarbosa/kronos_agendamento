package com.appagendamento.catalog.api;

import com.appagendamento.catalog.domain.Service;
import java.time.Instant;
import java.util.UUID;

public record ServiceResponse(
    UUID serviceId,
    String name,
    String description,
    int durationMinutes,
    int bufferAfterMinutes,
    String status,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  static ServiceResponse from(Service service) {
    return new ServiceResponse(
        service.serviceId(),
        service.name(),
        service.description(),
        service.durationMinutes(),
        service.bufferAfterMinutes(),
        service.status().name(),
        service.createdAt(),
        service.updatedAt(),
        service.version());
  }
}
