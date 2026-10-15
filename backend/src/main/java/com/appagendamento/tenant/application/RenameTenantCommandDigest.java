package com.appagendamento.tenant.application;

import com.appagendamento.idempotency.application.IdempotencyKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class RenameTenantCommandDigest {

  private RenameTenantCommandDigest() {}

  static String calculate(
      UUID tenantId, UUID targetId, String normalizedName, long expectedVersion) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream command = new DataOutputStream(bytes);
      write(command, "tenant.rename.canonical.v1");
      write(command, tenantId.toString());
      write(command, targetId.toString());
      command.writeLong(expectedVersion);
      write(command, normalizedName);
      command.flush();
      return IdempotencyKey.sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("Não foi possível canonicalizar o comando", exception);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }
}
