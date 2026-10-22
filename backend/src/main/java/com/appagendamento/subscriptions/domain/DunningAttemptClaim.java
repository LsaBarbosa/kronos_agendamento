package com.appagendamento.subscriptions.domain;

import java.time.Instant;
import java.util.UUID;

public record DunningAttemptClaim(
    UUID tenantId,
    UUID subscriptionId,
    UUID cycleId,
    UUID attemptId,
    UUID externalIdempotencyKey,
    UUID claimToken,
    int logicalSequence,
    int technicalAttempts,
    String workType,
    String planCode,
    Instant scheduledAt,
    Instant leaseUntil,
    String providerCode,
    String providerReference) {}
