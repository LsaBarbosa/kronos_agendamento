package com.appagendamento.appointments.application;

import com.appagendamento.appointments.domain.AppointmentStatus;
import java.time.Instant;
import java.util.UUID;

public record AdminAppointmentView(
    UUID appointmentId,
    UUID serviceId,
    String clientName,
    String serviceName,
    Instant startsAt,
    Instant serviceEndsAt,
    Instant occupiedUntil,
    String timeZone,
    int durationMinutes,
    int bufferAfterMinutes,
    AppointmentStatus status,
    Instant createdAt,
    long version) {}
