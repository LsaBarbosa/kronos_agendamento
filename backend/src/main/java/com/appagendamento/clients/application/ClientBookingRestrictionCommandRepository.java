package com.appagendamento.clients.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ClientBookingRestrictionCommandRepository {

  void lockTenant(UUID tenantId);

  Optional<Restriction> active(UUID tenantId, UUID clientId, UUID restrictionId);

  Optional<Restriction> activeForClient(UUID tenantId, UUID clientId);

  Optional<NoShowCause> unusedContributingCause(
      UUID tenantId, UUID clientId, Instant asOf, Instant windowStart);

  Optional<UUID> activateFromReconciliation(
      UUID tenantId,
      UUID clientId,
      NoShowCause cause,
      UUID administratorId,
      Instant asOf,
      int count,
      int threshold,
      long policyVersion);

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

  record NoShowCause(UUID appointmentId, UUID historyId) {}
}
