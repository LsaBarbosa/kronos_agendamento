package com.appagendamento.appointments.infrastructure;

import com.appagendamento.appointments.api.AppointmentOccupancy;
import com.appagendamento.appointments.api.AppointmentOccupancyQuery;
import com.appagendamento.appointments.api.ConfirmedAppointmentConflictProbe;
import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.appointments.application.AppointmentRepository;
import com.appagendamento.appointments.domain.Appointment;
import com.appagendamento.appointments.domain.AppointmentDataUnavailableException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
