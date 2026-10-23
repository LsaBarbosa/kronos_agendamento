package com.appagendamento.exports.domain;

import java.time.Instant;
import java.util.UUID;

public record ExportJobView(
    UUID exportId,
    UUID operationId,
    ExportFormat format,
    String category,
    ExportState state,
    ExportFilters filters,
    Instant snapshotAt,
    Integer retryNumber,
    Instant nextAttemptAt,
    Instant availableAt,
    Instant expiresAt,
    Integer rowCount,
    Long artifactSize,
    String artifactDigest,
    String failureCode,
    long version,
    Instant createdAt,
    Instant updatedAt) {}
