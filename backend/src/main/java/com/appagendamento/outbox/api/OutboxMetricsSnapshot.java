package com.appagendamento.outbox.api;

/** Bounded global projection; capped counts are lower bounds, never exact above the cap. */
public record OutboxMetricsSnapshot(
    long eligibleCount,
    long oldestEligibleAgeSeconds,
    long reconciliationRequiredCount,
    boolean capped) {}
