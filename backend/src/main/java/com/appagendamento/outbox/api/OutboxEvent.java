package com.appagendamento.outbox.api;

import java.util.UUID;

public record OutboxEvent(
    UUID eventId,
    UUID tenantId,
    String aggregateType,
    UUID aggregateId,
    long aggregateVersion,
    String eventType,
    int schemaVersion,
    UUID operationId,
    UUID correlationId,
    String payloadJson) {

  public OutboxEvent {
    if (eventId == null
        || tenantId == null
        || aggregateType == null
        || aggregateId == null
        || eventType == null
        || operationId == null
        || correlationId == null
        || payloadJson == null
        || aggregateVersion < 0
        || schemaVersion < 1) {
      throw new IllegalArgumentException("Campos obrigatórios do evento outbox ausentes");
    }
    boolean appointmentConfirmed =
        aggregateType.equals("APPOINTMENT") && eventType.equals("AppointmentConfirmed");
    boolean appointmentRescheduled =
        aggregateType.equals("APPOINTMENT") && eventType.equals("AppointmentRescheduled");
    boolean appointmentOutcome =
        aggregateType.equals("APPOINTMENT")
            && java.util.Set.of(
                    "AppointmentCancelled", "AppointmentCompleted", "ClientNoShowRecorded")
                .contains(eventType);
    boolean allowed =
        aggregateType.equals("TENANT") && eventType.equals("TENANT_RENAMED")
            || aggregateType.equals("SERVICE") && eventType.equals("ServiceCreated")
            || aggregateType.equals("SCHEDULE") && eventType.equals("ScheduleChanged")
            || aggregateType.equals("AVAILABILITY_POLICY")
                && eventType.equals("AvailabilityPolicyChanged")
            || appointmentConfirmed
            || appointmentRescheduled
            || appointmentOutcome;
    if (!allowed) {
      throw new IllegalArgumentException("Evento fora da allowlist desta integração");
    }
    String expectedPayloadPattern =
        appointmentConfirmed || appointmentRescheduled || appointmentOutcome
            ? "\\{\"appointmentId\":\"[0-9a-fA-F-]{36}\",\"version\":[0-9]+\\}"
            : aggregateType.equals("SERVICE")
                ? "\\{\"serviceId\":\"[0-9a-fA-F-]{36}\",\"version\":[0-9]+\\}"
                : "\\{\"tenantId\":\"[0-9a-fA-F-]{36}\",\"version\":[0-9]+\\}";
    if (!payloadJson.matches(expectedPayloadPattern)) {
      throw new IllegalArgumentException("Payload fora da allowlist do evento");
    }
  }
}
