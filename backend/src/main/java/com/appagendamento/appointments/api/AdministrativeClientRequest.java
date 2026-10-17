package com.appagendamento.appointments.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdministrativeClientRequest(
    @NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 80) String phone) {}
