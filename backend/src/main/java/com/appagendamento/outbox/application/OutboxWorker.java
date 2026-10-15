package com.appagendamento.outbox.application;

import com.appagendamento.outbox.api.DeliveryResult;
import com.appagendamento.outbox.api.OutboxClaim;
import com.appagendamento.outbox.api.OutboxPublisher;
import com.appagendamento.outbox.api.OutboxStore;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OutboxWorker {

  private static final Duration BASE_BACKOFF = Duration.ofSeconds(1);
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(15);
  private final OutboxStore store;
  private final ObjectProvider<OutboxPublisher> publishers;
  private final int batchSize;
  private final Duration leaseDuration;
  private final int maxAttempts;

  public OutboxWorker(
      OutboxStore store,
      ObjectProvider<OutboxPublisher> publishers,
      @Value("${app.outbox.worker.batch-size:20}") int batchSize,
      @Value("${app.outbox.worker.lease-seconds:60}") long leaseSeconds,
      @Value("${app.outbox.worker.max-attempts:8}") int maxAttempts) {
    if (batchSize < 1 || leaseSeconds < 1 || maxAttempts < 1) {
      throw new IllegalArgumentException("Configuração do worker outbox inválida");
    }
    this.store = store;
    this.publishers = publishers;
    this.batchSize = batchSize;
    this.leaseDuration = Duration.ofSeconds(leaseSeconds);
    this.maxAttempts = maxAttempts;
  }

  public int processBatch() {
    OutboxPublisher publisher = publishers.getIfAvailable();
    return publisher == null ? 0 : processBatch(publisher);
  }

  public int processBatch(OutboxPublisher publisher) {
    List<OutboxClaim> claims = store.claim(batchSize, leaseDuration);
    for (OutboxClaim claim : claims) {
      DeliveryResult result;
      try {
        result = publisher.publish(claim);
      } catch (RuntimeException failure) {
        result = DeliveryResult.OUTCOME_UNKNOWN;
      }
      switch (result) {
        case DELIVERED -> store.markDelivered(claim);
        case RETRYABLE_FAILURE ->
            store.markRetry(
                claim, backoff(claim.attemptCount()), maxAttempts, "DESTINATION_RETRYABLE");
        case TERMINAL_FAILURE -> store.markTerminal(claim, "DESTINATION_TERMINAL");
        case OUTCOME_UNKNOWN -> store.markOutcomeUnknown(claim, "DESTINATION_OUTCOME_UNKNOWN");
      }
    }
    return claims.size();
  }

  public boolean reprocess(UUID eventId) {
    return store.reprocess(eventId);
  }

  private static Duration backoff(int attempt) {
    long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
    Duration calculated = BASE_BACKOFF.multipliedBy(multiplier);
    return calculated.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : calculated;
  }
}
