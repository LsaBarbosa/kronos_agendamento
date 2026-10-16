package com.appagendamento.availability.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record ScheduleException(
    UUID exceptionId,
    UUID tenantId,
    LocalDate localDate,
    ScheduleExceptionKind kind,
    List<SpecialPeriod> periods,
    String reason,
    long version,
    String timeZone) {

  public ScheduleException {
    if (exceptionId == null
        || tenantId == null
        || localDate == null
        || kind == null
        || periods == null
        || version < 1
        || timeZone == null) {
      throw new ScheduleValidationException("Exceção de agenda inválida");
    }
    if (reason != null && (reason.isBlank() || reason.length() > 500)) {
      throw new ScheduleValidationException("Motivo opcional deve conter até 500 caracteres");
    }
    ZoneId zone = zone(timeZone);
    periods = canonicalPeriods(periods);
    if (kind == ScheduleExceptionKind.CLOSED && !periods.isEmpty()) {
      throw new ScheduleValidationException("Data fechada não pode conter períodos");
    }
    if (kind == ScheduleExceptionKind.SPECIAL && periods.isEmpty()) {
      throw new ScheduleValidationException("Horário especial exige ao menos um período");
    }
    SpecialPeriod previous = null;
    for (SpecialPeriod period : periods) {
      if (previous != null && period.localStart().isBefore(previous.localEnd())) {
        throw new ScheduleValidationException("Períodos especiais não podem se sobrepor");
      }
      resolveUnique(localDate.atTime(period.localStart()), zone);
      resolveUnique(localDate.atTime(period.localEnd()), zone);
      previous = period;
    }
  }

  public static List<SpecialPeriod> canonicalPeriods(List<SpecialPeriod> periods) {
    if (periods == null || periods.stream().anyMatch(java.util.Objects::isNull)) {
      throw new ScheduleValidationException("Períodos especiais são obrigatórios");
    }
    List<SpecialPeriod> sorted = new ArrayList<>(periods);
    sorted.sort(SpecialPeriod::compareTo);
    return List.copyOf(sorted);
  }

  public static ZoneId zone(String timeZone) {
    try {
      ZoneId zone = ZoneId.of(timeZone);
      if (!ZoneId.getAvailableZoneIds().contains(timeZone)) {
        throw new ScheduleValidationException("O fuso do tenant deve ser uma zona IANA");
      }
      return zone;
    } catch (RuntimeException exception) {
      if (exception instanceof ScheduleValidationException validation) {
        throw validation;
      }
      throw new ScheduleValidationException("O fuso do tenant deve ser uma zona IANA");
    }
  }

  public static ZoneOffset resolveUnique(LocalDateTime localDateTime, ZoneId zone) {
    List<ZoneOffset> offsets = zone.getRules().getValidOffsets(localDateTime);
    if (offsets.size() != 1) {
      throw new ScheduleValidationException(
          "Horário local inexistente ou ambíguo no fuso do tenant");
    }
    return offsets.getFirst();
  }
}
