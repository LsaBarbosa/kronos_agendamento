package com.appagendamento.outbox.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxStore {

  List<OutboxClaim> claim(int batchSize, Duration leaseDuration);

  boolean markDelivered(OutboxClaim claim);

  boolean markRetry(OutboxClaim claim, Duration backoff, int maxAttempts, String errorCode);

  boolean markTerminal(OutboxClaim claim, String errorCode);

  boolean markOutcomeUnknown(OutboxClaim claim, String errorCode);

  boolean reprocess(UUID eventId);

  default Optional<OutboxMetricsSnapshot> metricsSnapshot() {
    return Optional.empty();
  }
}
