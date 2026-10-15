package com.appagendamento.audit.api;

import java.time.Instant;
import java.util.UUID;

public record AuditEventView(
    UUID eventId,
    UUID tenantId,
    UUID actorId,
    String actorType,
    String actorRef,
    String action,
    String targetType,
    UUID targetId,
    String result,
    Instant occurredAt,
    Instant recordedAt,
    UUID correlationId,
    UUID traceId,
    String detailsJson) {}
