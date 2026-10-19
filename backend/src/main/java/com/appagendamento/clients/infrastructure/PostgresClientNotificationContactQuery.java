package com.appagendamento.clients.infrastructure;

import com.appagendamento.clients.api.ClientNotificationContactQuery;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresClientNotificationContactQuery implements ClientNotificationContactQuery {

  private final JdbcTemplate jdbc;

  public PostgresClientNotificationContactQuery(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<String> findE164Phone(UUID tenantId, UUID clientId) {
    return jdbc
        .query(
            "SELECT normalized_phone FROM agendamento.client WHERE tenant_id = ? AND client_id = ? AND status = 'ACTIVE'",
            (row, index) -> row.getString("normalized_phone"),
            tenantId,
            clientId)
        .stream()
        .findFirst();
  }
}
