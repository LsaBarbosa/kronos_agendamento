package com.appagendamento.clients.infrastructure;

import com.appagendamento.clients.application.ClientNoShowPolicyRepository;
import com.appagendamento.clients.domain.ClientNoShowPolicy;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresClientNoShowPolicyRepository implements ClientNoShowPolicyRepository {

  private final JdbcTemplate jdbc;

  public PostgresClientNoShowPolicyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ClientNoShowPolicy> findPolicy(UUID tenantId) {
    return jdbc
        .query(
            "SELECT tenant_id, threshold, version FROM agendamento.client_no_show_policy WHERE tenant_id = ?",
            (row, index) ->
                new ClientNoShowPolicy(
                    row.getObject("tenant_id", UUID.class),
                    row.getInt("threshold"),
                    row.getLong("version")),
            tenantId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<ClientNoShowPolicy> replacePolicy(
      UUID tenantId, int threshold, long expectedVersion) {
    if (expectedVersion == 0) {
      return jdbc
          .query(
              """
              INSERT INTO agendamento.client_no_show_policy (tenant_id, threshold, version)
              VALUES (?, ?, 1)
              ON CONFLICT (tenant_id) DO NOTHING
              RETURNING tenant_id, threshold, version
              """,
              PostgresClientNoShowPolicyRepository::mapPolicy,
              tenantId,
              threshold)
          .stream()
          .findFirst();
    }
    return jdbc
        .query(
            """
            UPDATE agendamento.client_no_show_policy
            SET threshold = ?, version = version + 1, updated_at = clock_timestamp()
            WHERE tenant_id = ? AND version = ?
            RETURNING tenant_id, threshold, version
            """,
            PostgresClientNoShowPolicyRepository::mapPolicy,
            threshold,
            tenantId,
            expectedVersion)
        .stream()
        .findFirst();
  }

  private static ClientNoShowPolicy mapPolicy(java.sql.ResultSet row, int index)
      throws java.sql.SQLException {
    return new ClientNoShowPolicy(
        row.getObject("tenant_id", UUID.class), row.getInt("threshold"), row.getLong("version"));
  }
}
