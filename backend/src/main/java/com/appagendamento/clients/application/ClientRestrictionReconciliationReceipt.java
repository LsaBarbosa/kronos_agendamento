package com.appagendamento.clients.application;

import java.time.Instant;
import java.util.UUID;

public record ClientRestrictionReconciliationReceipt(
    UUID clientId,
    String decision,
    Instant asOf,
    Instant windowStart,
    long count,
    int threshold,
    long policyVersion,
    UUID restrictionId,
    Long restrictionVersion) {}
