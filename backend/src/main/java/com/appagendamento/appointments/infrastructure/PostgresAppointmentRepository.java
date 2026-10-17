package com.appagendamento.appointments.infrastructure;

import com.appagendamento.appointments.api.AppointmentOccupancy;
import com.appagendamento.appointments.api.AppointmentOccupancyQuery;
import com.appagendamento.appointments.api.ConfirmedAppointmentConflictProbe;
import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.appointments.application.AdminAppointmentView;
import com.appagendamento.appointments.application.AppointmentHistoryEntry;
import com.appagendamento.appointments.application.AppointmentRepository;
import com.appagendamento.appointments.domain.Appointment;
import com.appagendamento.appointments.domain.AppointmentDataUnavailableException;
import com.appagendamento.appointments.domain.AppointmentStatus;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresAppointmentRepository
    implements AppointmentRepository,
        AppointmentOccupancyQuery,
        ConfirmedAppointmentConflictProbe,
        TenantAgendaLock {

  private final JdbcTemplate jdbc;

  public PostgresAppointmentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void acquire(UUID tenantId) {
    List<UUID> locked =
        jdbc.query(
            "SELECT tenant_id FROM agendamento.tenant WHERE tenant_id = ? FOR UPDATE",
            (row, index) -> row.getObject("tenant_id", UUID.class),
            tenantId);
    if (locked.isEmpty()) {
      throw new IllegalStateException("Tenant de agenda inexistente");
    }
  }

  @Override
  public List<AppointmentOccupancy> findOverlapping(
      UUID tenantId, Instant startsAt, Instant endsAt) {
    try {
      return jdbc.query(
          """
          SELECT starts_at, occupied_until FROM agendamento.appointment
          WHERE tenant_id = ? AND status = 'CONFIRMED'
            AND starts_at < ? AND occupied_until > ?
          ORDER BY starts_at, appointment_id
          """,
          (row, index) ->
              new AppointmentOccupancy(
                  row.getTimestamp("starts_at").toInstant(),
                  row.getTimestamp("occupied_until").toInstant()),
          tenantId,
          Timestamp.from(endsAt),
          Timestamp.from(startsAt));
    } catch (DataAccessException exception) {
      throw new AppointmentDataUnavailableException(exception);
    }
  }

  @Override
  public boolean overlapsConfirmed(UUID tenantId, Instant startsAt, Instant endsAt) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM agendamento.appointment
              WHERE tenant_id = ? AND status = 'CONFIRMED'
                AND starts_at < ? AND occupied_until > ?
            )
            """,
            Boolean.class,
            tenantId,
            Timestamp.from(endsAt),
            Timestamp.from(startsAt)));
  }

  @Override
  public void insertConfirmed(Appointment appointment, String operationId, String traceId) {
    try {
      jdbc.update(
          """
          INSERT INTO agendamento.appointment (
            appointment_id, tenant_id, client_id, service_id, starts_at, service_ends_at,
            occupied_until, time_zone, client_name_snapshot, service_name_snapshot,
            service_duration_minutes, service_buffer_after_minutes, service_price_minor_snapshot,
            service_currency_snapshot, status, created_at, version
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          appointment.appointmentId(),
          appointment.tenantId(),
          appointment.clientId(),
          appointment.serviceId(),
          Timestamp.from(appointment.startsAt()),
          Timestamp.from(appointment.serviceEndsAt()),
          Timestamp.from(appointment.occupiedUntil()),
          appointment.timeZone(),
          appointment.clientNameSnapshot(),
          appointment.serviceNameSnapshot(),
          appointment.serviceDurationMinutes(),
          appointment.serviceBufferAfterMinutes(),
          appointment.servicePriceMinorSnapshot(),
          appointment.serviceCurrencySnapshot(),
          appointment.status().name(),
          Timestamp.from(appointment.createdAt()),
          appointment.version());
      jdbc.update(
          """
          INSERT INTO agendamento.appointment_history (
            history_id, tenant_id, appointment_id, from_status, to_status,
            actor_type, actor_ref, reason_code, occurred_at
          ) VALUES (?, ?, ?, NULL, 'CONFIRMED', 'PUBLIC', 'PUBLIC_BOOKING',
                    'APPOINTMENT_CREATED', clock_timestamp())
          """,
          UUID.randomUUID(),
          appointment.tenantId(),
          appointment.appointmentId());
    } catch (DataIntegrityViolationException exception) {
      if (isAppointmentExclusionViolation(exception)) {
        throw new com.appagendamento.appointments.domain.SlotNoLongerAvailableException();
      }
      throw exception;
    }
  }

  @Override
  public String findTenantTimeZone(UUID tenantId) {
    try {
      return jdbc.queryForObject(
          "SELECT timezone FROM agendamento.tenant WHERE tenant_id = ?", String.class, tenantId);
    } catch (DataAccessException exception) {
      throw new AppointmentDataUnavailableException(exception);
    }
  }

  @Override
  public List<AdminAppointmentView> findAdminAppointments(
      UUID tenantId,
      Instant windowStart,
      Instant windowEnd,
      String status,
      UUID serviceId,
      Instant afterStartsAt,
      UUID afterAppointmentId,
      int limit) {
    try {
      return jdbc.query(
          """
          SELECT appointment_id, service_id, client_name_snapshot, service_name_snapshot,
                 starts_at, service_ends_at, occupied_until, time_zone,
                 service_duration_minutes, service_buffer_after_minutes, status, created_at, version
          FROM agendamento.appointment
          WHERE tenant_id = ?
            AND starts_at < ? AND occupied_until > ?
            AND (CAST(? AS text) IS NULL OR status = ?)
            AND (CAST(? AS uuid) IS NULL OR service_id = ?)
            AND (CAST(? AS timestamptz) IS NULL OR (starts_at, appointment_id) > (?, ?))
          ORDER BY starts_at ASC, appointment_id ASC
          LIMIT ?
          """,
          (row, index) ->
              new AdminAppointmentView(
                  row.getObject("appointment_id", UUID.class),
                  row.getObject("service_id", UUID.class),
                  row.getString("client_name_snapshot"),
                  row.getString("service_name_snapshot"),
                  row.getTimestamp("starts_at").toInstant(),
                  row.getTimestamp("service_ends_at").toInstant(),
                  row.getTimestamp("occupied_until").toInstant(),
                  row.getString("time_zone"),
                  row.getInt("service_duration_minutes"),
                  row.getInt("service_buffer_after_minutes"),
                  AppointmentStatus.valueOf(row.getString("status")),
                  row.getTimestamp("created_at").toInstant(),
                  row.getLong("version")),
          tenantId,
          Timestamp.from(windowEnd),
          Timestamp.from(windowStart),
          status,
          status,
          serviceId,
          serviceId,
          afterStartsAt == null ? null : Timestamp.from(afterStartsAt),
          afterStartsAt == null ? null : Timestamp.from(afterStartsAt),
          afterAppointmentId,
          limit);
    } catch (DataAccessException exception) {
      throw new AppointmentDataUnavailableException(exception);
    }
  }

  @Override
  public Optional<AdminAppointmentView> findAdminAppointment(UUID tenantId, UUID appointmentId) {
    try {
      List<AdminAppointmentView> result =
          jdbc.query(
              """
              SELECT appointment_id, service_id, client_name_snapshot, service_name_snapshot,
                     starts_at, service_ends_at, occupied_until, time_zone,
                     service_duration_minutes, service_buffer_after_minutes, status, created_at, version
              FROM agendamento.appointment
              WHERE tenant_id = ? AND appointment_id = ?
              """,
              (row, index) ->
                  new AdminAppointmentView(
                      row.getObject("appointment_id", UUID.class),
                      row.getObject("service_id", UUID.class),
                      row.getString("client_name_snapshot"),
                      row.getString("service_name_snapshot"),
                      row.getTimestamp("starts_at").toInstant(),
                      row.getTimestamp("service_ends_at").toInstant(),
                      row.getTimestamp("occupied_until").toInstant(),
                      row.getString("time_zone"),
                      row.getInt("service_duration_minutes"),
                      row.getInt("service_buffer_after_minutes"),
                      AppointmentStatus.valueOf(row.getString("status")),
                      row.getTimestamp("created_at").toInstant(),
                      row.getLong("version")),
              tenantId,
              appointmentId);
      return result.stream().findFirst();
    } catch (DataAccessException exception) {
      throw new AppointmentDataUnavailableException(exception);
    }
  }

  @Override
  public List<AppointmentHistoryEntry> findAdminAppointmentHistory(
      UUID tenantId, UUID appointmentId) {
    try {
      return jdbc.query(
          """
          SELECT from_status, to_status, actor_type, actor_ref, reason_code, occurred_at
          FROM agendamento.appointment_history
          WHERE tenant_id = ? AND appointment_id = ?
          ORDER BY occurred_at ASC, history_id ASC
          """,
          (row, index) ->
              new AppointmentHistoryEntry(
                  row.getString("from_status"),
                  row.getString("to_status"),
                  row.getString("actor_type"),
                  row.getString("actor_ref"),
                  row.getString("reason_code"),
                  row.getTimestamp("occurred_at").toInstant()),
          tenantId,
          appointmentId);
    } catch (DataAccessException exception) {
      throw new AppointmentDataUnavailableException(exception);
    }
  }

  private static boolean isAppointmentExclusionViolation(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sqlException
          && "23P01".equals(sqlException.getSQLState())
          && sqlException.getMessage() != null
          && sqlException.getMessage().contains("ex_appointment_tenant_occupied")) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
