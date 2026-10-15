package com.appagendamento.tenant.infrastructure;

import com.appagendamento.tenant.application.TenantRepository;
import com.appagendamento.tenant.domain.Tenant;
import com.appagendamento.tenant.domain.TenantSlug;
import com.appagendamento.tenant.domain.TenantSlugAlreadyExistsException;
import com.appagendamento.tenant.domain.TenantStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresTenantRepository implements TenantRepository {

  private static final RowMapper<Tenant> TENANT_ROW_MAPPER = PostgresTenantRepository::mapTenant;

  private final JdbcTemplate jdbc;

  public PostgresTenantRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Tenant insert(Tenant tenant) {
    List<Tenant> inserted =
        jdbc.query(
            """
            INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status, version)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT uq_tenant_slug DO NOTHING
            RETURNING tenant_id, name, slug, timezone, status, created_at, version
            """,
            TENANT_ROW_MAPPER,
            tenant.tenantId(),
            tenant.name(),
            tenant.slug().value(),
            tenant.timezone(),
            tenant.status().name(),
            tenant.version());
    if (inserted.isEmpty()) {
      throw new TenantSlugAlreadyExistsException();
    }
    return inserted.getFirst();
  }

  @Override
  public Optional<Tenant> findByTenantIdAndId(UUID authenticatedTenantId, UUID requestedTenantId) {
    return jdbc
        .query(
            """
            SELECT tenant_id, name, slug, timezone, status, created_at, version
            FROM agendamento.tenant
            WHERE tenant_id = ? AND tenant_id = ?
            """,
            TENANT_ROW_MAPPER,
            authenticatedTenantId,
            requestedTenantId)
        .stream()
        .findFirst();
  }

  @Override
  public boolean rename(
      UUID authenticatedTenantId, UUID requestedTenantId, String name, long expectedVersion) {
    return jdbc.query(
                """
            UPDATE agendamento.tenant
            SET name = ?, version = version + 1
            WHERE tenant_id = ? AND tenant_id = ? AND version = ?
            RETURNING tenant_id
            """,
                (result, row) -> result.getObject("tenant_id", UUID.class),
                name,
                requestedTenantId,
                authenticatedTenantId,
                expectedVersion)
            .size()
        == 1;
  }

  private static Tenant mapTenant(ResultSet result, int row) throws SQLException {
    return new Tenant(
        result.getObject("tenant_id", UUID.class),
        result.getString("name"),
        new TenantSlug(result.getString("slug")),
        result.getString("timezone"),
        TenantStatus.valueOf(result.getString("status")),
        result.getTimestamp("created_at").toInstant(),
        result.getLong("version"));
  }
}
