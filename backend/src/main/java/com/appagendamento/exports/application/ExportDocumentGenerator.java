package com.appagendamento.exports.application;

import com.appagendamento.exports.domain.ExportFormat;
import com.appagendamento.exports.domain.ExportSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

@Component
public class ExportDocumentGenerator {

  private static final List<String> CSV_HEADER =
      List.of(
          "appointment_id",
          "service_id",
          "service_name",
          "starts_at",
          "service_ends_at",
          "occupied_until",
          "time_zone",
          "duration_minutes",
          "buffer_after_minutes",
          "status",
          "created_at");
  private static final Charset WIN_ANSI = Charset.forName("windows-1252");

  public byte[] generate(ExportFormat format, ExportSnapshot snapshot) {
    return switch (format) {
      case CSV -> csv(snapshot);
      case PDF -> pdf(snapshot);
    };
  }

  private static byte[] csv(ExportSnapshot snapshot) {
    StringBuilder csv = new StringBuilder("\uFEFF");
    appendCsvRow(csv, CSV_HEADER);
    for (ExportSnapshot.Row row : snapshot.rows()) {
      appendCsvRow(
          csv,
          List.of(
              row.appointmentId().toString(),
              row.serviceId().toString(),
              row.serviceName(),
              row.startsAt().toString(),
              row.serviceEndsAt().toString(),
              row.occupiedUntil().toString(),
              row.timeZone(),
              Integer.toString(row.durationMinutes()),
              Integer.toString(row.bufferAfterMinutes()),
              row.status(),
              row.createdAt().toString()));
    }
    return csv.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void appendCsvRow(StringBuilder target, List<String> fields) {
    for (int index = 0; index < fields.size(); index++) {
      if (index > 0) target.append(',');
      target.append('"').append(csvSafe(fields.get(index)).replace("\"", "\"\"")).append('"');
    }
    target.append("\r\n");
  }

  static String csvSafe(String input) {
    String value = input == null ? "" : input;
    int index = 0;
    while (index < value.length()) {
      int codePoint = value.codePointAt(index);
      if (!Character.isWhitespace(codePoint) && !Character.isISOControl(codePoint)) break;
      index += Character.charCount(codePoint);
    }
    if (index < value.length() && "=+-@".indexOf(value.charAt(index)) >= 0) {
      return "'" + value;
    }
    return value;
  }

  private static byte[] pdf(ExportSnapshot snapshot) {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDPageContentStream stream = newPage(document, font, snapshot.timeZone());
      int line = 0;
      for (ExportSnapshot.Row row : snapshot.rows()) {
        if (line == 42) {
          stream.close();
          stream = newPage(document, font, snapshot.timeZone());
          line = 0;
        }
        String text =
            cleanPdfText(
                row.startsAt()
                    + " | "
                    + row.serviceName()
                    + " | "
                    + row.status()
                    + " | atendimento "
                    + row.durationMinutes()
                    + " min | buffer "
                    + row.bufferAfterMinutes()
                    + " min");
        stream.beginText();
        stream.setFont(font, 9);
        stream.newLineAtOffset(36, 744 - (line * 15));
        stream.showText(text.length() > 105 ? text.substring(0, 102) + "..." : text);
        stream.endText();
        line++;
      }
      stream.close();
      document.save(output);
      return output.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Falha ao gerar relatório PDF", exception);
    }
  }

  private static PDPageContentStream newPage(PDDocument document, PDType1Font font, String timeZone)
      throws IOException {
    PDPage page = new PDPage(PDRectangle.LETTER);
    document.addPage(page);
    PDPageContentStream stream = new PDPageContentStream(document, page);
    stream.beginText();
    stream.setFont(font, 13);
    stream.newLineAtOffset(36, 765);
    stream.showText("Agenda - fuso " + cleanPdfText(timeZone));
    stream.endText();
    return stream;
  }

  private static String cleanPdfText(String input) {
    StringBuilder result = new StringBuilder();
    for (int offset = 0; offset < input.length(); ) {
      int codePoint = input.codePointAt(offset);
      String candidate = new String(Character.toChars(codePoint));
      if (Character.isISOControl(codePoint)) result.append(' ');
      else result.append(WIN_ANSI.newEncoder().canEncode(candidate) ? candidate : "?");
      offset += Character.charCount(codePoint);
    }
    return result.toString();
  }
}
