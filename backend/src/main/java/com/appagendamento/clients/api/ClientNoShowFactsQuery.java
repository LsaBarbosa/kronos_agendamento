package com.appagendamento.clients.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientNoShowFactsQuery {

  Optional<ClientNoShowFactsPage> find(
      UUID tenantId,
      UUID clientId,
      Instant asOf,
      Instant windowStart,
      Instant afterOccurredAt,
      UUID afterHistoryId,
      int limit);

  record ClientNoShowFactsPage(String timeZone, long count, long totalFacts, List<Fact> facts) {}

  record Fact(
      UUID historyId,
      UUID appointmentId,
      Instant recordedAt,
      String currentStatus,
      long appointmentVersion,
      boolean contributes,
      UUID correctionHistoryId,
      Instant correctedAt,
      String correctionReason) {}
}
