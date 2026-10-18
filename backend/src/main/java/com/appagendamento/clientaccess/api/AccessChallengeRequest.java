package com.appagendamento.clientaccess.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccessChallengeRequest(@NotBlank @Size(max = 32) String phone) {}
