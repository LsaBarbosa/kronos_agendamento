package com.appagendamento.availability.api;

import com.appagendamento.availability.domain.BlockedPeriod;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

public record BlockedPeriodResponse(
    UUID blockId,
    Instant startsAt,
    Instant endsAt,
    LocalDateTime localStartsAt,
    LocalDateTime localEndsAt,
    String timeZone,
    String reason,
    String status,
    long version,
    Instant cancelledAt,
    String cancellationReason) {
  public static BlockedPeriodResponse from(BlockedPeriod value) {
    return new BlockedPeriodResponse(
        value.blockId(),
        value.startsAt(),
        value.endsAt(),
        value.startsAt().atZone(ZoneId.of(value.timeZone())).toLocalDateTime(),
        value.endsAt().atZone(ZoneId.of(value.timeZone())).toLocalDateTime(),
        value.timeZone(),
        value.reason(),
        value.status().name(),
        value.version(),
        value.cancelledAt(),
        value.cancellationReason());
  }
}
