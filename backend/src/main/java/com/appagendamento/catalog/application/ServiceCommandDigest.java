package com.appagendamento.catalog.application;

import com.appagendamento.catalog.domain.ServiceStatus;
import com.appagendamento.idempotency.application.IdempotencyKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class ServiceCommandDigest {

  private ServiceCommandDigest() {}

  static String create(
      UUID tenantId,
      String name,
      String description,
      int durationMinutes,
      int bufferAfterMinutes,
      ServiceStatus status) {
    return digest(
        "catalog.service.create.canonical.v1",
        tenantId,
        null,
        null,
        name == null ? null : com.appagendamento.catalog.domain.Service.canonicalDisplayName(name),
        description == null
            ? null
            : com.appagendamento.catalog.domain.Service.canonicalDescription(description),
        description != null,
        durationMinutes,
        bufferAfterMinutes,
        status.name());
  }

  static String update(
      UUID tenantId,
      UUID serviceId,
      long expectedVersion,
      String name,
      String description,
      Integer durationMinutes,
      Integer bufferAfterMinutes) {
    return digest(
        "catalog.service.update.canonical.v1",
        tenantId,
        serviceId,
        expectedVersion,
        name == null ? null : com.appagendamento.catalog.domain.Service.canonicalDisplayName(name),
        description == null
            ? null
            : com.appagendamento.catalog.domain.Service.canonicalDescription(description),
        description != null,
        durationMinutes,
        bufferAfterMinutes,
        "");
  }

  static String activation(
      UUID tenantId, UUID serviceId, long expectedVersion, ServiceStatus status) {
    return digest(
        "catalog.service.status.canonical.v1",
        tenantId,
        serviceId,
        expectedVersion,
        "",
        "",
        false,
        null,
        null,
        status.name());
  }

  private static String digest(
      String version,
      UUID tenantId,
      UUID serviceId,
      Long expectedVersion,
      String name,
      String description,
      boolean descriptionPresent,
      Integer durationMinutes,
      Integer bufferAfterMinutes,
      String status) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream command = new DataOutputStream(bytes);
      write(command, version);
      write(command, tenantId.toString());
      write(command, serviceId == null ? "" : serviceId.toString());
      command.writeBoolean(expectedVersion != null);
      if (expectedVersion != null) {
        command.writeLong(expectedVersion);
      }
      write(command, name == null ? "" : name.strip());
      command.writeBoolean(descriptionPresent);
      write(command, description == null ? "" : description.strip());
      writeInteger(command, durationMinutes);
      writeInteger(command, bufferAfterMinutes);
      write(command, status);
      command.flush();
      return IdempotencyKey.sha256(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("Não foi possível canonicalizar o comando", exception);
    }
  }

  private static void writeInteger(DataOutputStream output, Integer value) throws IOException {
    output.writeBoolean(value != null);
    if (value != null) {
      output.writeInt(value);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] content = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(content.length);
    output.write(content);
  }
}
