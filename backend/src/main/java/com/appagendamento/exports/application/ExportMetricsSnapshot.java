package com.appagendamento.exports.application;

public record ExportMetricsSnapshot(
    long eligibleCount, long oldestEligibleAgeSeconds, boolean capped) {}
