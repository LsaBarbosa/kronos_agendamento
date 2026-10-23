package com.appagendamento.exports.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ExportRequestBody(
    @NotNull ExportFormatBody format,
    @NotBlank @Size(max = 32) String category,
    @NotNull @Valid Filters filters) {

  public record Filters(
      @NotNull LocalDate from,
      @NotNull LocalDate to,
      @Size(max = 5) List<String> statuses,
      UUID serviceId) {}

  public enum ExportFormatBody {
    CSV,
    PDF
  }
}
