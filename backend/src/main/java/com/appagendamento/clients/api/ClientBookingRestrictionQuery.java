package com.appagendamento.clients.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientBookingRestrictionQuery {

  ClientBookingRestrictionStatus status(UUID tenantId, UUID clientId);

  default Optional<ClientBookingRestriction> active(UUID tenantId, UUID clientId) {
    return Optional.empty();
  }

  default List<ClientBookingRestrictionHistory> history(UUID tenantId, UUID clientId) {
    return List.of();
  }

  record ClientBookingRestriction(
      UUID restrictionId,
      ClientBookingRestrictionStatus status,
      java.time.Instant evaluatedAt,
      int countAtActivation,
      int thresholdAtActivation,
      long policyVersion,
      long version) {}

  record ClientBookingRestrictionHistory(
      UUID historyId,
      UUID restrictionId,
      String eventType,
      String actorType,
      java.time.Instant occurredAt,
      int countAtDecision,
      int thresholdAtDecision,
      long policyVersion,
      long restrictionVersion,
      String reason) {}
}
