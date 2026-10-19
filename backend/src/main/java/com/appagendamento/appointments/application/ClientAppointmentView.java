package com.appagendamento.appointments.application;

import java.time.Instant;
import java.util.UUID;

public record ClientAppointmentView(
    UUID appointmentId,
    String serviceName,
    Instant startsAt,
    Instant serviceEndsAt,
    String timeZone,
    String status,
    long version) {}
