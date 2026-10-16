package com.appagendamento.availability.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

public record BlockedPeriod(
    UUID blockId,
    UUID tenantId,
    Instant startsAt,
    Instant endsAt,
    String timeZone,
    String reason,
    BlockedPeriodStatus status,
    long version,
    Instant cancelledAt,
    String cancellationReason) {

  public BlockedPeriod {
    if (blockId == null
        || tenantId == null
        || startsAt == null
        || endsAt == null
        || !startsAt.isBefore(endsAt)
        || timeZone == null
        || status == null
        || version < 1) {
      throw new ScheduleValidationException("Bloqueio inválido");
    }
    ZoneId zone = ScheduleException.zone(timeZone);
    requireQuarterHour(startsAt.atZone(zone));
    requireQuarterHour(endsAt.atZone(zone));
    if (reason == null || reason.isBlank() || reason.length() > 500) {
      throw new ScheduleValidationException(
          "Motivo do bloqueio é obrigatório e limitado a 500 caracteres");
    }
    if (status == BlockedPeriodStatus.ACTIVE
        && (cancelledAt != null || cancellationReason != null)) {
      throw new ScheduleValidationException("Bloqueio ativo não pode conter dados de cancelamento");
    }
    if (status == BlockedPeriodStatus.CANCELLED
        && (cancelledAt == null
            || cancellationReason == null
            || cancellationReason.isBlank()
            || cancellationReason.length() > 500)) {
      throw new ScheduleValidationException("Cancelamento exige instante e motivo válidos");
    }
  }

  public BlockedPeriod cancel(Instant cancelledAt, String cancellationReason) {
    if (status != BlockedPeriodStatus.ACTIVE) {
      throw new ScheduleValidationException("Somente bloqueio ativo pode ser cancelado");
    }
    return new BlockedPeriod(
        blockId,
        tenantId,
        startsAt,
        endsAt,
        timeZone,
        reason,
        BlockedPeriodStatus.CANCELLED,
        version + 1,
        cancelledAt,
        cancellationReason);
  }

  private static void requireQuarterHour(ZonedDateTime value) {
    if (value.getMinute() % 15 != 0 || value.getSecond() != 0 || value.getNano() != 0) {
      throw new ScheduleValidationException(
          "Limites locais do bloqueio devem alinhar a 15 minutos");
    }
  }
}
