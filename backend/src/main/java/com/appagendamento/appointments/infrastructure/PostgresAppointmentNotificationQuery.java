package com.appagendamento.appointments.infrastructure;

import com.appagendamento.appointments.api.AppointmentNotificationQuery;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresAppointmentNotificationQuery implements AppointmentNotificationQuery {

  private final JdbcTemplate jdbc;

  public PostgresAppointmentNotificationQuery(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<NotificationAppointment> find(UUID tenantId, UUID appointmentId) {
    return jdbc
        .query(
            """
        SELECT tenant_id, appointment_id, client_id, version, status, client_name_snapshot,
               service_name_snapshot,
               starts_at, service_ends_at, time_zone
        FROM agendamento.appointment
        WHERE tenant_id = ? AND appointment_id = ?
        """,
            (row, index) ->
                new NotificationAppointment(
                    row.getObject("tenant_id", UUID.class),
                    row.getObject("appointment_id", UUID.class),
                    row.getObject("client_id", UUID.class),
                    row.getLong("version"),
                    row.getString("status"),
                    row.getString("client_name_snapshot"),
                    row.getString("service_name_snapshot"),
                    row.getTimestamp("starts_at").toInstant(),
                    row.getTimestamp("service_ends_at").toInstant(),
                    row.getString("time_zone")),
            tenantId,
            appointmentId)
        .stream()
        .findFirst();
  }
}
