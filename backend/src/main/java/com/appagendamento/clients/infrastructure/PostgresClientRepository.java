package com.appagendamento.clients.infrastructure;

import com.appagendamento.clients.api.AdministrativeClientRegistry;
import com.appagendamento.clients.api.ClientAccountLookup;
import com.appagendamento.clients.application.ClientRepository;
import com.appagendamento.clients.domain.ClientName;
import com.appagendamento.clients.domain.E164Phone;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresClientRepository
    implements ClientRepository, AdministrativeClientRegistry, ClientAccountLookup {

  private final JdbcTemplate jdbc;

  public PostgresClientRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public UUID findOrCreate(UUID tenantId, ClientName displayName, E164Phone normalizedPhone) {
    UUID candidateId = UUID.randomUUID();
    List<UUID> inserted =
        jdbc.query(
            """
            INSERT INTO agendamento.client
              (client_id, tenant_id, display_name, normalized_phone)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (tenant_id, normalized_phone) DO NOTHING
            RETURNING client_id
            """,
            (result, row) -> result.getObject("client_id", UUID.class),
            candidateId,
            tenantId,
            displayName.value(),
            normalizedPhone.value());
    if (!inserted.isEmpty()) {
      return inserted.getFirst();
    }

    return jdbc.queryForObject(
        """
        SELECT client_id
        FROM agendamento.client
        WHERE tenant_id = ? AND normalized_phone = ?
        """,
        UUID.class,
        tenantId,
        normalizedPhone.value());
  }

  @Override
  public java.util.Optional<UUID> findExisting(UUID tenantId, E164Phone normalizedPhone) {
    return findClientId(tenantId, normalizedPhone.value());
  }

  @Override
  public java.util.Optional<UUID> findClientId(UUID tenantId, String normalizedPhone) {
    return jdbc
        .query(
            "SELECT client_id FROM agendamento.client WHERE tenant_id = ? AND normalized_phone = ?",
            (row, index) -> row.getObject("client_id", UUID.class),
            tenantId,
            normalizedPhone)
        .stream()
        .findFirst();
  }

  public java.util.Optional<UUID> findExisting(UUID tenantId, String normalizedPhone) {
    return findClientId(tenantId, normalizedPhone);
  }

  @Override
  public java.util.Optional<AdministrativeClientView> find(UUID tenantId, UUID clientId) {
    return jdbc
        .query(
            """
            SELECT client_id, tenant_id, display_name, status
            FROM agendamento.client
            WHERE tenant_id = ? AND client_id = ?
            """,
            (row, index) ->
                new AdministrativeClientView(
                    row.getObject("client_id", UUID.class),
                    row.getObject("tenant_id", UUID.class),
                    row.getString("display_name"),
                    row.getString("status")),
            tenantId,
            clientId)
        .stream()
        .findFirst();
  }

  @Override
  public UUID findOrCreate(UUID tenantId, String name, String phone) {
    return findOrCreate(tenantId, ClientName.of(name), E164Phone.fromNormalized(phone));
  }

  @Override
  public List<AdministrativeClientView> search(UUID tenantId, String query, int limit) {
    return jdbc.query(
        """
        SELECT client_id, tenant_id, display_name, status
        FROM agendamento.client
        WHERE tenant_id = ? AND status = 'ACTIVE' AND display_name ILIKE ?
        ORDER BY display_name, client_id
        LIMIT ?
        """,
        (row, index) ->
            new AdministrativeClientView(
                row.getObject("client_id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("display_name"),
                row.getString("status")),
        tenantId,
        "%" + query.replace("%", "\\%").replace("_", "\\_") + "%",
        limit);
  }
}
