package com.appagendamento.appointments.infrastructure;

import com.appagendamento.appointments.api.AppointmentRetentionFactsQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresAppointmentRetentionFactsQuery implements AppointmentRetentionFactsQuery {

  private static final int LIMIT = 10_000;
  private static final int QUERY_TIMEOUT_SECONDS = 2;
  private final JdbcTemplate jdbc;

  public PostgresAppointmentRetentionFactsQuery(JdbcTemplate jdbc) {
    this.jdbc = new JdbcTemplate(jdbc.getDataSource());
    this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
  }

  @Override
  public Facts query(UUID tenantId, Instant referenceAt) {
    return jdbc.queryForObject(
        """
        SELECT
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.appointment
            WHERE tenant_id = ? LIMIT ?) sampled) AS appointments,
          (SELECT count(*) FROM (SELECT 1 FROM agendamento.appointment
            WHERE tenant_id = ? AND status = 'CONFIRMED' AND starts_at > ? LIMIT ?) sampled)
            AS future_confirmed
        """,
        (row, index) ->
            new Facts(count(row.getInt("appointments")), count(row.getInt("future_confirmed"))),
        tenantId,
        LIMIT + 1,
        tenantId,
        Timestamp.from(referenceAt),
        LIMIT + 1);
  }

  private static Count count(int observed) {
    return new Count(Math.min(observed, LIMIT), observed > LIMIT);
  }
}
