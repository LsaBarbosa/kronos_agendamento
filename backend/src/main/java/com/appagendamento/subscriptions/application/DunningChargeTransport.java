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
      Instant dueAt,
      long amountMinorUnits,
      String currency,
      String termsVersion,
      String offerVersion) {
    public ChargeRequest {
      if (amountMinorUnits <= 0
          || currency == null
          || !currency.matches("[A-Z]{3}")
          || termsVersion == null
          || termsVersion.isBlank()
          || offerVersion == null
          || offerVersion.isBlank()) {
        throw new IllegalArgumentException("Snapshot contratual de cobrança inválido");
      }
    }
  }

  record TransportResult(
      Outcome outcome,
      String providerCode,
      String providerAccountId,
      String providerReference,
      String safeErrorCode) {
    public TransportResult(
        Outcome outcome, String providerCode, String providerReference, String safeErrorCode) {
      this(outcome, providerCode, null, providerReference, safeErrorCode);
    }

    public TransportResult {
      if (outcome == null) throw new IllegalArgumentException("Resultado obrigatório");
      if (providerCode != null && !providerCode.matches("[A-Za-z0-9_-]{1,48}")) {
        throw new IllegalArgumentException("Identificador do provedor inválido");
      }
      if (providerAccountId != null
          && (providerAccountId.isBlank() || providerAccountId.length() > 128)) {
        throw new IllegalArgumentException("Conta do provedor inválida");
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
