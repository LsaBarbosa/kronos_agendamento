package com.appagendamento.publicpage.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record PublicContactRequest(
    @NotBlank @Size(min = 6, max = 32) String contactPhone, @PositiveOrZero long expectedVersion) {}
