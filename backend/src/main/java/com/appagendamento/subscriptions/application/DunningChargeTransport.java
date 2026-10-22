package com.appagendamento.subscriptions.application;

import java.time.Instant;
import java.util.UUID;

/** Optional provider boundary. Production has no implementation until a provider is approved. */
public interface DunningChargeTransport {

  TransportResult submit(ChargeRequest request);

  record ChargeRequest(
      UUID tenantId,
      UUID subscriptionId,
      UUID cycleId,
      UUID attemptId,
      UUID externalIdempotencyKey,
      int logicalSequence,
      String planCode,
      Instant dueAt) {}

  record TransportResult(
      Outcome outcome, String providerCode, String providerReference, String safeErrorCode) {
    public TransportResult {
      if (outcome == null) throw new IllegalArgumentException("Resultado obrigatório");
      if (providerCode != null && !providerCode.matches("[A-Za-z0-9_-]{1,48}")) {
        throw new IllegalArgumentException("Identificador do provedor inválido");
      }
      if (safeErrorCode != null && !safeErrorCode.matches("[A-Z0-9_]{1,48}")) {
        throw new IllegalArgumentException("Código operacional inválido");
      }
      if (providerReference != null
          && (providerReference.isBlank() || providerReference.length() > 192)) {
        throw new IllegalArgumentException("Referência do provedor inválida");
      }
    }
  }

  enum Outcome {
    ACCEPTED,
    RETRYABLE_NO_EFFECT,
    DECLINED,
    FAILED_FINAL,
    OUTCOME_UNKNOWN
  }
}
