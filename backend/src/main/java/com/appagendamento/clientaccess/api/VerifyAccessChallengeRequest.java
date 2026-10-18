package com.appagendamento.clientaccess.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyAccessChallengeRequest(@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}
