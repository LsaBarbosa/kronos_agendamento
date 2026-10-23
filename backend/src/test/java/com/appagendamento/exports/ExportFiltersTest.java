package com.appagendamento.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.appointments.domain.AppointmentStatus;
import com.appagendamento.exports.domain.ExportFilters;
import com.appagendamento.exports.domain.InvalidExportRequestException;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExportFiltersTest {

  @Test
  void acceptsExactlyThreeHundredSixtySixInclusiveLocalDates() {
    var filters =
        new ExportFilters(
            LocalDate.parse("2024-01-01"),
            LocalDate.parse("2024-12-31"),
            Set.of(AppointmentStatus.NO_SHOW),
            null);

    assertThat(filters.from()).isEqualTo(LocalDate.parse("2024-01-01"));
    assertThat(filters.to()).isEqualTo(LocalDate.parse("2024-12-31"));
  }

  @Test
  void rejectsInvertedOrExcessiveDateWindows() {
    assertThatThrownBy(
            () ->
                new ExportFilters(
                    LocalDate.parse("2025-01-02"), LocalDate.parse("2025-01-01"), Set.of(), null))
        .isInstanceOf(InvalidExportRequestException.class);
    assertThatThrownBy(
            () ->
                new ExportFilters(
                    LocalDate.parse("2024-01-01"), LocalDate.parse("2025-01-01"), Set.of(), null))
        .isInstanceOf(InvalidExportRequestException.class);
  }
}
