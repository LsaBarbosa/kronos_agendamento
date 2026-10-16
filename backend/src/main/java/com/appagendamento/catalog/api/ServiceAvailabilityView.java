package com.appagendamento.catalog.api;

import java.util.UUID;

public record ServiceAvailabilityView(
    UUID serviceId, UUID tenantId, boolean active, int durationMinutes, int bufferAfterMinutes) {}
