package com.appagendamento.catalog.domain;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public record Service(
    UUID serviceId,
    UUID tenantId,
    String name,
    String normalizedName,
    String description,
    int durationMinutes,
    int bufferAfterMinutes,
    ServiceStatus status,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  public Service {
    if (serviceId == null
        || tenantId == null
        || status == null
        || createdAt == null
        || updatedAt == null) {
      throw new IllegalArgumentException("Dados obrigatórios do serviço ausentes");
    }
    name = canonicalDisplayName(name);
    String expectedNormalizedName = normalizeForComparison(name);
    if (!expectedNormalizedName.equals(normalizedName)) {
      throw new IllegalArgumentException("Nome normalizado inconsistente");
    }
    description = canonicalDescription(description);
    if (durationMinutes <= 0 || durationMinutes % 15 != 0) {
      throw new IllegalArgumentException("Duração deve ser positiva e múltipla de 15 minutos");
    }
    if (bufferAfterMinutes < 0 || bufferAfterMinutes % 15 != 0) {
      throw new IllegalArgumentException("Buffer deve ser não negativo e múltiplo de 15 minutos");
    }
    if (version < 0) {
      throw new IllegalArgumentException("Version não pode ser negativa");
    }
  }

  public static Service create(
      UUID serviceId,
      UUID tenantId,
      String name,
      String description,
      int durationMinutes,
      int bufferAfterMinutes,
      ServiceStatus status,
      Instant now) {
    String normalizedName = canonicalDisplayName(name);
    return new Service(
        serviceId,
        tenantId,
        normalizedName,
        normalizeForComparison(normalizedName),
        description,
        durationMinutes,
        bufferAfterMinutes,
        status,
        now,
        now,
        0);
  }

  public Service update(
      String newName,
      String newDescription,
      Integer newDurationMinutes,
      Integer newBufferAfterMinutes,
      Instant now) {
    String updatedName = newName == null ? name : canonicalDisplayName(newName);
    String updatedDescription =
        newDescription == null ? description : canonicalDescription(newDescription);
    int updatedDuration = newDurationMinutes == null ? durationMinutes : newDurationMinutes;
    int updatedBuffer = newBufferAfterMinutes == null ? bufferAfterMinutes : newBufferAfterMinutes;
    return new Service(
        serviceId,
        tenantId,
        updatedName,
        normalizeForComparison(updatedName),
        updatedDescription,
        updatedDuration,
        updatedBuffer,
        status,
        createdAt,
        now,
        version + 1);
  }

  public Service withStatus(ServiceStatus newStatus, Instant now) {
    if (status == newStatus) {
      return this;
    }
    return new Service(
        serviceId,
        tenantId,
        name,
        normalizedName,
        description,
        durationMinutes,
        bufferAfterMinutes,
        newStatus,
        createdAt,
        now,
        version + 1);
  }

  public static String normalizeForComparison(String value) {
    return Normalizer.normalize(canonicalDisplayName(value), Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT);
  }

  public static String canonicalDisplayName(String value) {
    String normalized =
        value == null ? "" : Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("Nome do serviço é obrigatório");
    }
    return normalized;
  }

  public static String canonicalDescription(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip();
    return normalized.isEmpty() ? null : normalized;
  }
}
