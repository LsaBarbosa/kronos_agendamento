package com.appagendamento.appointments.application;

import com.appagendamento.appointments.domain.AppointmentStatus;
import java.util.UUID;

public record AdministrativeAppointmentOutcomeReceipt(
    UUID appointmentId, UUID operationId, AppointmentStatus status, long version) {}
