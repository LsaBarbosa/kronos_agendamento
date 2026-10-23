package com.appagendamento.exports.application;

import com.appagendamento.exports.domain.ExportFilters;
import com.appagendamento.exports.domain.ExportFormat;
import com.appagendamento.idempotency.api.CommandDigest;
import com.appagendamento.idempotency.application.IdempotencyKey;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public final class ExportCommandDigest implements CommandDigest {

  private final String value;

  public ExportCommandDigest(ExportFormat format, String category, ExportFilters filters) {
    String statuses =
        filters.statuses().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    String canonical =
        "admin-export:v1|"
            + format.name()
            + "|"
            + category
            + "|"
            + filters.from()
            + "|"
            + filters.to()
            + "|"
            + statuses
            + "|"
            + (filters.serviceId() == null ? "" : filters.serviceId());
    value = IdempotencyKey.sha256(canonical.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String persistedValue() {
    return value;
  }

  @Override
  public boolean matches(String storedValue) {
    return value.equals(storedValue);
  }
}
