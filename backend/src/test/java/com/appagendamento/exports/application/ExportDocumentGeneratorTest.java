package com.appagendamento.exports.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.appagendamento.exports.domain.ExportFormat;
import com.appagendamento.exports.domain.ExportSnapshot;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ExportDocumentGeneratorTest {

  private final ExportDocumentGenerator generator = new ExportDocumentGenerator();

  @Test
  void csvEscapesRfc4180FieldsAndNeutralizesFormulaAfterLeadingWhitespace() {
    var snapshot =
        new ExportSnapshot(
            "America/Sao_Paulo",
            List.of(
                new ExportSnapshot.Row(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    " \t=HYPERLINK(\"https://unsafe.invalid\")",
                    OffsetDateTime.parse("2026-10-23T09:00:00-03:00"),
                    OffsetDateTime.parse("2026-10-23T09:30:00-03:00"),
                    OffsetDateTime.parse("2026-10-23T09:45:00-03:00"),
                    "America/Sao_Paulo",
                    30,
                    15,
                    "CONFIRMED",
                    OffsetDateTime.parse("2026-10-01T12:00:00-03:00"))));

    String csv =
        new String(
            generator.generate(ExportFormat.CSV, snapshot),
            java.nio.charset.StandardCharsets.UTF_8);

    assertThat(csv)
        .startsWith("\uFEFF\"appointment_id\",\"service_id\"")
        .contains("\"' \t=HYPERLINK(\"\"https://unsafe.invalid\"\")\"")
        .contains("\r\n")
        .doesNotContain("client_name", "phone", "PRIVATE CLIENT");
  }

  @Test
  void csvNeutralizesFormulaAfterUnicodeSpaceAndFormatCharacters() {
    assertThat(ExportDocumentGenerator.csvSafe("\u00a0\ufeff=1+1")).isEqualTo("'\u00a0\ufeff=1+1");
  }

  @Test
  void pdfIsReadableAndContainsTenantScopedAppointmentSummary() throws Exception {
    var snapshot =
        new ExportSnapshot(
            "America/Sao_Paulo",
            List.of(
                new ExportSnapshot.Row(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "Clínica São João",
                    OffsetDateTime.parse("2026-10-23T09:00:00-03:00"),
                    OffsetDateTime.parse("2026-10-23T09:30:00-03:00"),
                    OffsetDateTime.parse("2026-10-23T09:45:00-03:00"),
                    "America/Sao_Paulo",
                    30,
                    15,
                    "CONFIRMED",
                    OffsetDateTime.parse("2026-10-01T12:00:00-03:00"))));

    byte[] pdf = generator.generate(ExportFormat.PDF, snapshot);
    String text;
    try (var document = Loader.loadPDF(pdf)) {
      text = new PDFTextStripper().getText(document);
    }

    assertThat(pdf).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F');
    assertThat(text)
        .contains("Agenda", "America/Sao_Paulo", "Clínica São João", "CONFIRMED", "buffer 15 min");
    assertThat(text).doesNotContain("PRIVATE CLIENT", "phone", "audit");
  }
}
