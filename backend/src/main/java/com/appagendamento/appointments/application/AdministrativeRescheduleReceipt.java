package com.appagendamento.appointments.application;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdministrativeRescheduleReceipt(
    UUID appointmentId,
    UUID operationId,
    long version,
    OffsetDateTime startsAt,
    OffsetDateTime serviceEndsAt,
    OffsetDateTime occupiedUntil,
    String timeZone) {}
