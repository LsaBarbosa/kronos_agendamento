package com.appagendamento.outbox.api;

import java.util.UUID;

public record OutboxClaim(
    UUID eventId,
    UUID tenantId,
    String aggregateType,
    UUID aggregateId,
    long aggregateVersion,
    String eventType,
    int schemaVersion,
    UUID operationId,
    UUID correlationId,
    String payloadJson,
    int attemptCount,
    UUID claimToken) {}
