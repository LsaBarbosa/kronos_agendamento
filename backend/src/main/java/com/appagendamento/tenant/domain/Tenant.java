package com.appagendamento.tenant.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

public record Tenant(
    UUID tenantId,
    String name,
    TenantSlug slug,
    String timezone,
    TenantStatus status,
    Instant createdAt,
    long version) {

  private static final Set<String> IANA_TIMEZONES = ZoneId.getAvailableZoneIds();

  public Tenant {
    if (tenantId == null || slug == null || status == null || createdAt == null) {
      throw new IllegalArgumentException("Dados obrigatórios do tenant ausentes");
    }
    name = normalizeName(name);
    if (timezone == null || !IANA_TIMEZONES.contains(timezone)) {
      throw new IllegalArgumentException("Timezone IANA inválido");
    }
    if (version < 0) {
      throw new IllegalArgumentException("Version não pode ser negativa");
    }
  }

  public static Tenant create(UUID tenantId, String name, String slug, String timezone) {
    return new Tenant(
        tenantId, name, new TenantSlug(slug), timezone, TenantStatus.ACTIVE, Instant.EPOCH, 0);
  }

  public Tenant rename(String newName) {
    return new Tenant(tenantId, newName, slug, timezone, status, createdAt, version + 1);
  }

  private static String normalizeName(String value) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty() || normalized.length() > 200) {
      throw new IllegalArgumentException("Nome do tenant inválido");
    }
    return normalized;
  }
}
