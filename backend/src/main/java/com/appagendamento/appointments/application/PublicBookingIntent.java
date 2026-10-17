package com.appagendamento.appointments.application;

import java.time.Instant;
import java.util.UUID;

public record PublicBookingIntent(
    UUID tenantId,
    String commandScope,
    String keyDigest,
    String recoveryDigest,
    String requestDigest,
    Instant expiresAt) {}
