package com.appagendamento.availability.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;

public record LocalTimeResolution(Instant instant, LocalDateTime localDateTime, ZoneOffset offset) {

  public static LocalTimeResolution resolve(String value, ZoneId zone) {
    if (value == null || value.isBlank()) {
      throw new ScheduleValidationException("Limite temporal é obrigatório");
    }
    try {
      OffsetDateTime offsetDateTime = OffsetDateTime.parse(value);
      LocalDateTime local = offsetDateTime.toLocalDateTime();
      validateQuarterHour(local);
      List<ZoneOffset> validOffsets = zone.getRules().getValidOffsets(local);
      if (!validOffsets.contains(offsetDateTime.getOffset())) {
        throw new ScheduleValidationException("Offset não corresponde ao fuso IANA do tenant");
      }
      return new LocalTimeResolution(offsetDateTime.toInstant(), local, offsetDateTime.getOffset());
    } catch (DateTimeParseException ignored) {
      try {
        LocalDateTime local = LocalDateTime.parse(value);
        validateQuarterHour(local);
        ZoneOffset offset = ScheduleException.resolveUnique(local, zone);
        return new LocalTimeResolution(local.toInstant(offset), local, offset);
      } catch (DateTimeParseException exception) {
        throw new ScheduleValidationException("Use data e hora ISO local ou ISO 8601 com offset");
      }
    }
  }

  private static void validateQuarterHour(LocalDateTime value) {
    if (value.getMinute() % 15 != 0 || value.getSecond() != 0 || value.getNano() != 0) {
      throw new ScheduleValidationException("Limites locais devem alinhar a 15 minutos");
    }
  }
}
