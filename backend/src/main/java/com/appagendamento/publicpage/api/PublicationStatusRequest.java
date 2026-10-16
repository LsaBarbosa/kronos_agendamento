package com.appagendamento.publicpage.api;

import jakarta.validation.constraints.PositiveOrZero;

public record PublicationStatusRequest(boolean published, @PositiveOrZero long expectedVersion) {}
