package com.appagendamento.appointments.application;

import java.time.Instant;

public record AppointmentHistoryEntry(
    String fromStatus,
    String toStatus,
    String actorType,
    String actorRef,
    String reasonCode,
    Instant occurredAt) {}
