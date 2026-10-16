package com.appagendamento.appointments.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

public record Appointment(
    UUID appointmentId,
    UUID tenantId,
    UUID clientId,
    UUID serviceId,
    Instant startsAt,
    Instant serviceEndsAt,
    Instant occupiedUntil,
    String timeZone,
    String clientNameSnapshot,
    String serviceNameSnapshot,
    int serviceDurationMinutes,
    int serviceBufferAfterMinutes,
    Long servicePriceMinorSnapshot,
    String serviceCurrencySnapshot,
    AppointmentStatus status,
    Instant createdAt,
    long version) {

  public Appointment {
    if (appointmentId == null
        || tenantId == null
        || clientId == null
        || serviceId == null
        || startsAt == null
        || serviceEndsAt == null
        || occupiedUntil == null
        || timeZone == null
        || clientNameSnapshot == null
        || serviceNameSnapshot == null
        || status == null
        || createdAt == null) {
      throw new IllegalArgumentException("Dados obrigatórios da reserva ausentes");
    }
    if (!serviceEndsAt.isAfter(startsAt)
        || occupiedUntil.isBefore(serviceEndsAt)
        || !serviceEndsAt.equals(startsAt.plusSeconds(serviceDurationMinutes * 60L))
        || !occupiedUntil.equals(serviceEndsAt.plusSeconds(serviceBufferAfterMinutes * 60L))
        || serviceDurationMinutes <= 0
        || serviceBufferAfterMinutes < 0
        || version < 1) {
      throw new IllegalArgumentException("Intervalo, snapshot ou versão da reserva inválidos");
    }
    if ((servicePriceMinorSnapshot == null) != (serviceCurrencySnapshot == null)) {
      throw new IllegalArgumentException(
          "Preço e moeda do snapshot devem estar ambos ausentes ou presentes");
    }
    if (servicePriceMinorSnapshot != null && servicePriceMinorSnapshot < 0) {
      throw new IllegalArgumentException("Preço do snapshot inválido");
    }
    ZoneId.of(timeZone);
  }
}
