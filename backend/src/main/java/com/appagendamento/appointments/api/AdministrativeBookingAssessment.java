package com.appagendamento.appointments.api;

import com.appagendamento.appointments.domain.AdministrativeOverridePolicy;
import java.time.Instant;
import java.util.List;

public record AdministrativeBookingAssessment(
    Instant startsAt,
    Instant serviceEndsAt,
    Instant occupiedUntil,
    String timeZone,
    List<AdministrativeOverridePolicy> violatedPolicies) {

  public AdministrativeBookingAssessment {
    if (startsAt == null
        || serviceEndsAt == null
        || occupiedUntil == null
        || timeZone == null
        || violatedPolicies == null
        || !startsAt.isBefore(serviceEndsAt)
        || serviceEndsAt.isAfter(occupiedUntil)) {
      throw new IllegalArgumentException("Avaliação de reserva administrativa inválida");
    }
    violatedPolicies = List.copyOf(violatedPolicies);
  }
}
