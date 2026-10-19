package com.appagendamento.appointments.application;

import java.util.UUID;

public record ClientAppointmentCancellationReceipt(
    UUID appointmentId, UUID operationId, String status, long version) {}
