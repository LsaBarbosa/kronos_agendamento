package com.appagendamento.subscriptions.application;

public record DunningMetricsSnapshot(
    long backlog,
    long overdue,
    long oldestOverdueSeconds,
    long outcomeUnknown,
    long acceptedTransport,
    long declined,
    long failedFinal,
    long skippedLateRecovery,
    long technicalAttempts,
    boolean capped) {}
