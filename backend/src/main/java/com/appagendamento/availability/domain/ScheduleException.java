package com.appagendamento.availability.domain;

import java.time.Instant;
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
    Instant previousEndInstant = null;
    for (SpecialPeriod period : periods) {
      if (previous != null && period.localStart().isBefore(previous.localEnd())) {
        throw new ScheduleValidationException("Períodos especiais não podem se sobrepor");
      }
      ZoneOffset startOffset =
          resolveOffset(localDate.atTime(period.localStart()), zone, period.localStartOffset());
      ZoneOffset endOffset =
          resolveOffset(localDate.atTime(period.localEnd()), zone, period.localEndOffset());
      Instant startInstant = localDate.atTime(period.localStart()).toInstant(startOffset);
      Instant endInstant = localDate.atTime(period.localEnd()).toInstant(endOffset);
      if (!startInstant.isBefore(endInstant)) {
        throw new ScheduleValidationException(
            "Os offsets informados devem manter o início antes do fim no tempo real");
      }
      if (previousEndInstant != null && previousEndInstant.isAfter(startInstant)) {
        throw new ScheduleValidationException(
            "Períodos especiais não podem se sobrepor no tempo real após aplicar os offsets");
      }
      previousEndInstant = endInstant;
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
    return resolveOffset(localDateTime, zone, null);
  }

  public static ZoneOffset resolveOffset(
      LocalDateTime localDateTime, ZoneId zone, ZoneOffset requestedOffset) {
    List<ZoneOffset> offsets = zone.getRules().getValidOffsets(localDateTime);
    if (offsets.isEmpty()) {
      throw new InvalidLocalTimeException(
          "Esse horário local não existe por causa da mudança de fuso. Escolha outro horário.");
    }
    if (requestedOffset != null) {
      if (!offsets.contains(requestedOffset)) {
        throw new InvalidLocalTimeException(
            "O offset não é válido para esse horário e fuso. Escolha uma ocorrência válida.");
      }
      return requestedOffset;
    }
    if (offsets.size() > 1) {
      throw new InvalidLocalTimeException(
          "Esse horário local ocorre duas vezes. Informe um offset válido ou escolha outro horário.");
    }
    return offsets.getFirst();
  }
}
