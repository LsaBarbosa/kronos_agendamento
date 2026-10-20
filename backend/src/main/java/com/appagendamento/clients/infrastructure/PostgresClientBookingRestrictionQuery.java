package com.appagendamento.clients.infrastructure;

import com.appagendamento.clients.api.ClientBookingRestrictionQuery;
import com.appagendamento.clients.api.ClientBookingRestrictionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresClientBookingRestrictionQuery implements ClientBookingRestrictionQuery {

  private final JdbcTemplate jdbc;

  public PostgresClientBookingRestrictionQuery(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public ClientBookingRestrictionStatus status(UUID tenantId, UUID clientId) {
    if (active(tenantId, clientId).isPresent()) return ClientBookingRestrictionStatus.RESTRICTED;
    Boolean configured =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM agendamento.client_no_show_policy WHERE tenant_id = ?)",
            Boolean.class,
            tenantId);
    return Boolean.TRUE.equals(configured)
        ? ClientBookingRestrictionStatus.CLEAR
        : ClientBookingRestrictionStatus.NOT_CONFIGURED;
  }

  @Override
  public Optional<ClientBookingRestriction> active(UUID tenantId, UUID clientId) {
    return jdbc
        .query(
            "SELECT restriction_id, evaluated_at, count_at_activation, threshold_at_activation, policy_version, version "
                + "FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ? AND status = 'ACTIVE'",
            (row, index) ->
                new ClientBookingRestriction(
                    row.getObject("restriction_id", UUID.class),
                    ClientBookingRestrictionStatus.RESTRICTED,
                    row.getTimestamp("evaluated_at").toInstant(),
                    row.getInt("count_at_activation"),
                    row.getInt("threshold_at_activation"),
                    row.getLong("policy_version"),
                    row.getLong("version")),
            tenantId,
            clientId)
        .stream()
        .findFirst();
  }

  @Override
  public List<ClientBookingRestrictionHistory> history(UUID tenantId, UUID clientId) {
    return jdbc.query(
        "SELECT h.history_id, h.restriction_id, h.event_type, h.actor_type, h.occurred_at, "
            + "h.count_at_decision, h.threshold_at_decision, h.policy_version, h.restriction_version, h.reason "
            + "FROM agendamento.client_booking_restriction_history h JOIN agendamento.client_booking_restriction r "
            + "ON r.tenant_id = h.tenant_id AND r.restriction_id = h.restriction_id "
            + "WHERE r.tenant_id = ? AND r.client_id = ? ORDER BY h.occurred_at DESC, h.history_id DESC",
        (row, index) ->
            new ClientBookingRestrictionHistory(
                row.getObject("history_id", UUID.class),
                row.getObject("restriction_id", UUID.class),
                row.getString("event_type"),
                row.getString("actor_type"),
                row.getTimestamp("occurred_at").toInstant(),
                row.getInt("count_at_decision"),
                row.getInt("threshold_at_decision"),
                row.getLong("policy_version"),
                row.getLong("restriction_version"),
                row.getString("reason")),
        tenantId,
        clientId);
  }
}
