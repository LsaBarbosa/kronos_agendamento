package com.appagendamento.appointments.application;

import com.appagendamento.appointments.domain.AppointmentStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AdministrativeAppointmentReceipt(
    UUID appointmentId,
    UUID operationId,
    AppointmentStatus status,
    String serviceName,
    OffsetDateTime startsAt,
    OffsetDateTime serviceEndsAt,
    OffsetDateTime occupiedUntil,
    String timeZone) {}
