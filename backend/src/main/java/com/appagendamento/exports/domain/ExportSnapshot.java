package com.appagendamento.exports.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ExportSnapshot(String timeZone, List<Row> rows) {

  public ExportSnapshot {
    rows = List.copyOf(rows);
  }

  public record Row(
      UUID appointmentId,
      UUID serviceId,
      String serviceName,
      OffsetDateTime startsAt,
      OffsetDateTime serviceEndsAt,
      OffsetDateTime occupiedUntil,
      String timeZone,
      int durationMinutes,
      int bufferAfterMinutes,
      String status,
      OffsetDateTime createdAt) {}
}
