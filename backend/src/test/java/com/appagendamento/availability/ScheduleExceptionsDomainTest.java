package com.appagendamento.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.availability.domain.BlockedPeriod;
import com.appagendamento.availability.domain.BlockedPeriodStatus;
import com.appagendamento.availability.domain.LocalTimeResolution;
import com.appagendamento.availability.domain.ScheduleDateRange;
import com.appagendamento.availability.domain.ScheduleException;
import com.appagendamento.availability.domain.ScheduleExceptionKind;
import com.appagendamento.availability.domain.ScheduleValidationException;
import com.appagendamento.availability.domain.SpecialPeriod;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScheduleExceptionsDomainTest {

  @Test
  void acceptsAdjacentSpecialPeriodsButRejectsOverlapAndInvalidLocalTimes() {
    var periods =
        List.of(
            new SpecialPeriod(
                java.time.LocalTime.parse("09:00"), java.time.LocalTime.parse("10:00")),
            new SpecialPeriod(
                java.time.LocalTime.parse("10:00"), java.time.LocalTime.parse("11:00")));
    var value = exception(ScheduleExceptionKind.SPECIAL, periods);
    assertThat(value.periods()).hasSize(2);
    assertThatThrownBy(
            () ->
                exception(
                    ScheduleExceptionKind.SPECIAL,
                    List.of(
                        new SpecialPeriod(
                            java.time.LocalTime.parse("09:00"), java.time.LocalTime.parse("10:00")),
                        new SpecialPeriod(
                            java.time.LocalTime.parse("09:45"),
                            java.time.LocalTime.parse("11:00")))))
        .isInstanceOf(ScheduleValidationException.class);
    assertThatThrownBy(
            () ->
                new SpecialPeriod(
                    java.time.LocalTime.parse("09:10"), java.time.LocalTime.parse("10:00")))
        .isInstanceOf(ScheduleValidationException.class);
    assertThatThrownBy(
            () ->
                new SpecialPeriod(
                    java.time.LocalTime.parse("23:00"), java.time.LocalTime.parse("00:00")))
        .isInstanceOf(ScheduleValidationException.class);
  }

  @Test
  void distinguishesClosedFromAbsentAndValidatesDstAndExplicitOffsets() {
    var closed = exception(ScheduleExceptionKind.CLOSED, List.of());
    assertThat(closed.kind()).isEqualTo(ScheduleExceptionKind.CLOSED);
    var zone = ZoneId.of("America/New_York");
    assertThatThrownBy(() -> LocalTimeResolution.resolve("2026-03-08T02:15", zone))
        .isInstanceOf(ScheduleValidationException.class);
    assertThatThrownBy(() -> LocalTimeResolution.resolve("2026-11-01T01:15", zone))
        .isInstanceOf(ScheduleValidationException.class);
    assertThat(LocalTimeResolution.resolve("2026-11-01T01:15-04:00", zone).instant())
        .isEqualTo(Instant.parse("2026-11-01T05:15:00Z"));
    assertThatThrownBy(() -> LocalTimeResolution.resolve("2026-11-01T01:15-03:00", zone))
        .isInstanceOf(ScheduleValidationException.class);
  }

  @Test
  void preservesRealDurationAcrossOffsetChangeAndRequiresCancellationReason() {
    ZoneId zone = ZoneId.of("America/New_York");
    Instant start = LocalTimeResolution.resolve("2026-03-08T01:45-05:00", zone).instant();
    Instant end = LocalTimeResolution.resolve("2026-03-08T03:15-04:00", zone).instant();
    BlockedPeriod block =
        new BlockedPeriod(
            UUID.randomUUID(),
            UUID.randomUUID(),
            start,
            end,
            zone.getId(),
            "Manutenção",
            BlockedPeriodStatus.ACTIVE,
            1,
            null,
            null);
    assertThat(java.time.Duration.between(block.startsAt(), block.endsAt()).toMinutes())
        .isEqualTo(30);
    assertThatThrownBy(() -> block.cancel(Instant.now(), " "))
        .isInstanceOf(ScheduleValidationException.class);
    assertThat(block.cancel(Instant.now(), "Concluído").version()).isEqualTo(2);
  }

  @Test
  void limitsDateQueriesTo366InclusiveDates() {
    new ScheduleDateRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"));
    assertThatThrownBy(
            () ->
                new ScheduleDateRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-02")))
        .isInstanceOf(ScheduleValidationException.class);
  }

  private static ScheduleException exception(
      ScheduleExceptionKind kind, List<SpecialPeriod> periods) {
    return new ScheduleException(
        UUID.randomUUID(),
        UUID.randomUUID(),
        LocalDate.parse("2026-06-10"),
        kind,
        periods,
        null,
        1,
        "America/Sao_Paulo");
  }
}
