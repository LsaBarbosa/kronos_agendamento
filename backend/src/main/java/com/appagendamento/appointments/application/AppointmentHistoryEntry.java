package com.appagendamento.appointments.application;

import java.time.Instant;

public record AppointmentHistoryEntry(
    String fromStatus,
    String toStatus,
    String actorType,
    String actorRef,
    String reasonCode,
    String reason,
    Instant occurredAt,
    Instant previousStartsAt,
    Instant previousOccupiedUntil,
    Instant newStartsAt,
    Instant newOccupiedUntil,
    Long versionBefore,
    Long versionAfter) {}
