package com.appagendamento.availability.application;

import com.appagendamento.idempotency.application.IdempotencyKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

final class BlockedPeriodCommandDigest {

  private BlockedPeriodCommandDigest() {}

  static String calculateCreate(
      UUID tenantId, String timeZone, Instant startsAt, Instant endsAt, String reason) {
    return digest(
        "availability.blocked-period.create.canonical.v1",
        tenantId,
        null,
        timeZone,
        startsAt,
        endsAt,
        reason);
  }

  static String calculateCancel(UUID tenantId, UUID blockId, long expectedVersion, String reason) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream command = new DataOutputStream(bytes);
      write(command, "availability.blocked-period.cancel.canonical.v1");
      write(command, tenantId.toString());
      write(command, blockId.toString());
      command.writeLong(expectedVersion);
      write(command, reason);
      command.flush();
      return IdempotencyKey.sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("Não foi possível canonicalizar o cancelamento", exception);
    }
  }

  private static String digest(
      String scope,
      UUID tenantId,
      UUID blockId,
      String timeZone,
      Instant startsAt,
      Instant endsAt,
      String reason) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream command = new DataOutputStream(bytes);
      write(command, scope);
      write(command, tenantId.toString());
      write(command, blockId == null ? "" : blockId.toString());
      write(command, timeZone);
      write(command, startsAt.toString());
      write(command, endsAt.toString());
      write(command, reason);
      command.flush();
      return IdempotencyKey.sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("Não foi possível canonicalizar o bloqueio", exception);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] content = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(content.length);
    output.write(content);
  }
}
