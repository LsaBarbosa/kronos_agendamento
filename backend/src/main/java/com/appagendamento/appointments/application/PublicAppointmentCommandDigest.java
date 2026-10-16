package com.appagendamento.appointments.application;

import com.appagendamento.idempotency.application.IdempotencyKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

final class PublicAppointmentCommandDigest {

  private PublicAppointmentCommandDigest() {}

  static String create(
      UUID tenantId,
      String slug,
      UUID serviceId,
      OffsetDateTime startsAt,
      String canonicalName,
      String normalizedPhone) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream command = new DataOutputStream(bytes);
      write(command, "appointments.public.create.canonical.v1");
      write(command, tenantId.toString());
      write(command, slug);
      write(command, serviceId.toString());
      write(command, startsAt.toInstant().toString());
      write(command, startsAt.getOffset().toString());
      write(command, canonicalName);
      write(command, normalizedPhone);
      command.flush();
      return IdempotencyKey.sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("Não foi possível canonicalizar a reserva", exception);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] content = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(content.length);
    output.write(content);
  }
}
