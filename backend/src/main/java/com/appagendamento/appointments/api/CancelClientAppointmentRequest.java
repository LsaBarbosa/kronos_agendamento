package com.appagendamento.appointments.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CancelClientAppointmentRequest(@NotNull @Positive Long expectedVersion) {}
