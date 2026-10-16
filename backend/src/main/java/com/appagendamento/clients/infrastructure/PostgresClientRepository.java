package com.appagendamento.clients.infrastructure;

import com.appagendamento.clients.application.ClientRepository;
import com.appagendamento.clients.domain.ClientName;
import com.appagendamento.clients.domain.E164Phone;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresClientRepository implements ClientRepository {

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
}
