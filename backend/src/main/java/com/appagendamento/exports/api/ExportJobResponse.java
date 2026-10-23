package com.appagendamento.exports.api;

import com.appagendamento.exports.domain.ExportJobView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ExportJobResponse(
    UUID exportId,
    UUID operationId,
    String format,
    String category,
    String state,
    Filters filters,
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
    Instant updatedAt) {

  public static ExportJobResponse from(ExportJobView view) {
    return new ExportJobResponse(
        view.exportId(),
        view.operationId(),
        view.format().name(),
        view.category(),
        view.state().name(),
        new Filters(
            view.filters().from(),
            view.filters().to(),
            view.filters().statuses().stream().map(Enum::name).sorted().toList(),
            view.filters().serviceId()),
        view.snapshotAt(),
        view.retryNumber(),
        view.nextAttemptAt(),
        view.availableAt(),
        view.expiresAt(),
        view.rowCount(),
        view.artifactSize(),
        view.artifactDigest(),
        view.failureCode(),
        view.version(),
        view.createdAt(),
        view.updatedAt());
  }

  public record Filters(LocalDate from, LocalDate to, List<String> statuses, UUID serviceId) {}
}
