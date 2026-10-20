package com.appagendamento.appointments.infrastructure;

import com.appagendamento.clients.api.ClientNoShowFactsQuery;
import com.appagendamento.clients.api.ClientNoShowFactsUnavailableException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresClientNoShowFactsQuery implements ClientNoShowFactsQuery {

  private final JdbcTemplate jdbc;

  public PostgresClientNoShowFactsQuery(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ClientNoShowFactsPage> find(
      UUID tenantId,
      UUID clientId,
      Instant asOf,
      Instant windowStart,
      Instant afterOccurredAt,
      UUID afterHistoryId,
      int limit) {
    try {
      List<String> zones =
          jdbc.query(
              """
              SELECT t.timezone FROM agendamento.client c
              JOIN agendamento.tenant t ON t.tenant_id = c.tenant_id
              WHERE c.tenant_id = ? AND c.client_id = ?
              """,
              (row, index) -> row.getString("timezone"),
              tenantId,
              clientId);
      if (zones.isEmpty()) return Optional.empty();
      Long count =
          jdbc.queryForObject(
              """
              SELECT count(DISTINCT a.appointment_id) FROM agendamento.appointment_history h
              JOIN agendamento.appointment a
                ON a.tenant_id = h.tenant_id AND a.appointment_id = h.appointment_id
              LEFT JOIN agendamento.appointment_history correction
                ON correction.tenant_id = h.tenant_id
               AND correction.appointment_id = h.appointment_id
               AND correction.corrects_history_id = h.history_id
               AND correction.occurred_at <= ?
              WHERE h.tenant_id = ? AND a.client_id = ?
                AND h.to_status = 'NO_SHOW' AND h.reason_code = 'APPOINTMENT_NO_SHOW'
                AND h.occurred_at >= ? AND h.occurred_at < ?
                AND correction.history_id IS NULL
              """,
              Long.class,
              Timestamp.from(asOf),
              tenantId,
              clientId,
              Timestamp.from(windowStart),
              Timestamp.from(asOf));
      Long totalFacts =
          jdbc.queryForObject(
              """
              SELECT count(DISTINCT a.appointment_id) FROM agendamento.appointment_history h
              JOIN agendamento.appointment a
                ON a.tenant_id = h.tenant_id AND a.appointment_id = h.appointment_id
              WHERE h.tenant_id = ? AND a.client_id = ?
                AND h.to_status = 'NO_SHOW' AND h.reason_code = 'APPOINTMENT_NO_SHOW'
                AND h.occurred_at >= ? AND h.occurred_at < ?
              """,
              Long.class,
              tenantId,
              clientId,
              Timestamp.from(windowStart),
              Timestamp.from(asOf));
      List<Fact> facts =
          jdbc.query(
              """
              SELECT history_id, appointment_id, occurred_at,
                     status, version, correction_history_id, corrected_at, correction_reason
              FROM (
                SELECT DISTINCT ON (h.tenant_id, h.appointment_id)
                       h.history_id, h.appointment_id, h.occurred_at,
                       a.status, a.version, correction.history_id AS correction_history_id,
                       correction.occurred_at AS corrected_at, correction.reason AS correction_reason
                FROM agendamento.appointment_history h
                JOIN agendamento.appointment a
                  ON a.tenant_id = h.tenant_id AND a.appointment_id = h.appointment_id
                LEFT JOIN agendamento.appointment_history correction
                  ON correction.tenant_id = h.tenant_id
                 AND correction.appointment_id = h.appointment_id
                 AND correction.corrects_history_id = h.history_id
                 AND correction.occurred_at <= ?
                WHERE h.tenant_id = ? AND a.client_id = ?
                  AND h.to_status = 'NO_SHOW' AND h.reason_code = 'APPOINTMENT_NO_SHOW'
                  AND h.occurred_at >= ? AND h.occurred_at < ?
                ORDER BY h.tenant_id, h.appointment_id, h.occurred_at, h.history_id
              ) unique_facts
              WHERE (CAST(? AS timestamptz) IS NULL OR (occurred_at, history_id) < (?, ?))
              ORDER BY occurred_at DESC, history_id DESC
              LIMIT ?
              """,
              (row, index) -> {
                UUID correctionId = row.getObject("correction_history_id", UUID.class);
                return new Fact(
                    row.getObject("history_id", UUID.class),
                    row.getObject("appointment_id", UUID.class),
                    row.getTimestamp("occurred_at").toInstant(),
                    row.getString("status"),
                    row.getLong("version"),
                    correctionId == null,
                    correctionId,
                    row.getTimestamp("corrected_at") == null
                        ? null
                        : row.getTimestamp("corrected_at").toInstant(),
                    row.getString("correction_reason"));
              },
              Timestamp.from(asOf),
              tenantId,
              clientId,
              Timestamp.from(windowStart),
              Timestamp.from(asOf),
              afterOccurredAt == null ? null : Timestamp.from(afterOccurredAt),
              afterOccurredAt == null ? null : Timestamp.from(afterOccurredAt),
              afterHistoryId,
              limit);
      return Optional.of(new ClientNoShowFactsPage(zones.getFirst(), count, totalFacts, facts));
    } catch (DataAccessException exception) {
      throw new ClientNoShowFactsUnavailableException(exception);
    }
  }
}
