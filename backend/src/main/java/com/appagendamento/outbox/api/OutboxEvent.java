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
                    "AppointmentCancelled",
                    "AppointmentCompleted",
                    "ClientNoShowRecorded",
                    "ClientNoShowCorrected")
                .contains(eventType);
    boolean clientRestriction =
        aggregateType.equals("CLIENT_RESTRICTION")
            && java.util.Set.of("ClientSelfBookingBlocked", "ClientSelfBookingUnblocked")
                .contains(eventType);
    boolean subscriptionLifecycle =
        aggregateType.equals("SUBSCRIPTION")
            && java.util.Set.of(
                    "SubscriptionReactivated",
                    "SubscriptionPeriodExtended",
                    "SubscriptionPastDue",
                    "SubscriptionPaymentSuspended")
                .contains(eventType);
    boolean exportLifecycle =
        aggregateType.equals("EXPORT")
            && java.util.Set.of("ExportRequested", "ExportAvailable", "ExportExpired")
                .contains(eventType);
    boolean allowed =
        aggregateType.equals("TENANT") && eventType.equals("TENANT_RENAMED")
            || aggregateType.equals("SERVICE") && eventType.equals("ServiceCreated")
            || aggregateType.equals("SCHEDULE") && eventType.equals("ScheduleChanged")
            || aggregateType.equals("AVAILABILITY_POLICY")
                && eventType.equals("AvailabilityPolicyChanged")
            || appointmentConfirmed
            || appointmentRescheduled
            || appointmentOutcome
            || clientRestriction
            || subscriptionLifecycle
            || exportLifecycle;
    if (!allowed) {
      throw new IllegalArgumentException("Evento fora da allowlist desta integração");
    }
    String expectedPayloadPattern =
        appointmentConfirmed || appointmentRescheduled || appointmentOutcome
            ? "\\{\"appointmentId\":\"[0-9a-fA-F-]{36}\",\"version\":[0-9]+\\}"
            : exportLifecycle
                ? "\\{\"exportId\":\"[0-9a-fA-F-]{36}\",\"version\":[1-9][0-9]*\\}"
                : eventType.equals("ClientSelfBookingBlocked")
                    ? "\\{\"clientId\":\"[0-9a-fA-F-]{36}\",\"restrictionId\":\"[0-9a-fA-F-]{36}\",\"count\":[1-9][0-9]*,\"threshold\":[1-5],\"policyVersion\":[1-9][0-9]*,\"version\":1\\}"
                    : eventType.equals("ClientSelfBookingUnblocked")
                        ? "\\{\"clientId\":\"[0-9a-fA-F-]{36}\",\"restrictionId\":\"[0-9a-fA-F-]{36}\",\"version\":(?:[2-9]|[1-9][0-9]+)\\}"
                        : subscriptionLifecycle
                            ? "\\{\\\"subscriptionId\\\":\\\"[0-9a-fA-F-]{36}\\\",\\\"version\\\":[1-9][0-9]*\\}"
                            : aggregateType.equals("SERVICE")
                                ? "\\{\"serviceId\":\"[0-9a-fA-F-]{36}\",\"version\":[0-9]+\\}"
                                : "\\{\"tenantId\":\"[0-9a-fA-F-]{36}\",\"version\":[0-9]+\\}";
    if (!payloadJson.matches(expectedPayloadPattern)) {
      throw new IllegalArgumentException("Payload fora da allowlist do evento");
    }
  }
}
