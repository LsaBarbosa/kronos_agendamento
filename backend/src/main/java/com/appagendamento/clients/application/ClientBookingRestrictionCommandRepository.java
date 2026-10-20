package com.appagendamento.clients.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ClientBookingRestrictionCommandRepository {

  void lockTenant(UUID tenantId);

  Optional<Restriction> active(UUID tenantId, UUID clientId, UUID restrictionId);

  boolean lift(
      UUID tenantId,
      UUID clientId,
      UUID restrictionId,
      long expectedVersion,
      UUID administratorId,
      Instant occurredAt,
      int currentCount,
      String reason);

  record Restriction(
      UUID restrictionId,
      long version,
      int countAtActivation,
      int thresholdAtActivation,
      long policyVersion) {}
}
