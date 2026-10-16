package com.appagendamento.appointments.application;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PublicAppointmentReceipt(
    UUID appointmentId,
    UUID operationId,
    String status,
    String serviceName,
    OffsetDateTime startsAt,
    OffsetDateTime serviceEndsAt,
    String timeZone) {}
