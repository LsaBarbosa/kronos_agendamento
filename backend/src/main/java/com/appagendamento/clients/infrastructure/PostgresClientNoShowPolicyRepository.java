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
    return jdbc
        .query(
            """
            INSERT INTO agendamento.client_no_show_policy (tenant_id, threshold, version)
            SELECT ?, ?, 1 WHERE ? = 0
            ON CONFLICT (tenant_id) DO UPDATE
            SET threshold = EXCLUDED.threshold,
                version = agendamento.client_no_show_policy.version + 1,
                updated_at = clock_timestamp()
            WHERE agendamento.client_no_show_policy.version = ?
            RETURNING tenant_id, threshold, version
            """,
            (row, index) ->
                new ClientNoShowPolicy(
                    row.getObject("tenant_id", UUID.class),
                    row.getInt("threshold"),
                    row.getLong("version")),
            tenantId,
            threshold,
            expectedVersion,
            expectedVersion)
        .stream()
        .findFirst();
  }
}
