package com.appagendamento.publicpage.api;

import com.appagendamento.publicpage.domain.PublicService;
import java.util.UUID;

public record PublicServiceResponse(
    UUID serviceId, String name, String description, int durationMinutes) {

  public static PublicServiceResponse from(PublicService service) {
    return new PublicServiceResponse(
        service.serviceId(), service.name(), service.description(), service.durationMinutes());
  }
}
