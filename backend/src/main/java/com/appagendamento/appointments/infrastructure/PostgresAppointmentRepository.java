package com.appagendamento.appointments.infrastructure;

import com.appagendamento.appointments.api.AppointmentOccupancy;
import com.appagendamento.appointments.api.AppointmentOccupancyQuery;
import com.appagendamento.appointments.api.ConfirmedAppointmentConflictProbe;
import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.appointments.application.AdminAppointmentView;
import com.appagendamento.appointments.application.AppointmentCreationContext;
import com.appagendamento.appointments.application.AppointmentHistoryEntry;
import com.appagendamento.appointments.application.AppointmentRepository;
import com.appagendamento.appointments.application.ClientAppointmentView;
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
  public List<AppointmentOccupancy> findOverlappingExcluding(
      UUID tenantId, Instant startsAt, Instant endsAt, UUID excludedAppointmentId) {
    try {
      return jdbc.query(
          """
          SELECT starts_at, occupied_until FROM agendamento.appointment
          WHERE tenant_id = ? AND status = 'CONFIRMED' AND appointment_id <> ?
            AND starts_at < ? AND occupied_until > ?
          ORDER BY starts_at, appointment_id
          """,
          (row, index) ->
              new AppointmentOccupancy(
                  row.getTimestamp("starts_at").toInstant(),
                  row.getTimestamp("occupied_until").toInstant()),
          tenantId,
          excludedAppointmentId,
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
    insertConfirmed(appointment, operationId, traceId, AppointmentCreationContext.publicBooking());
  }

  @Override
  public void insertConfirmed(
      Appointment appointment,
      String operationId,
      String traceId,
      com.appagendamento.appointments.application.AppointmentCreationContext context) {
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
            actor_type, actor_ref, reason_code, reason, occurred_at
          ) VALUES (?, ?, ?, NULL, 'CONFIRMED', ?, ?, ?, ?, clock_timestamp())
          """,
          UUID.randomUUID(),
          appointment.tenantId(),
          appointment.appointmentId(),
          context.actorType(),
          context.actorReference(),
          context.reasonCode(),
          context.reason());
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
  public List<ClientAppointmentView> findClientAppointments(
      UUID tenantId,
      UUID clientId,
      boolean futureOnly,
      Instant now,
      Instant afterStartsAt,
      UUID afterAppointmentId,
      int limit) {
    try {
      return jdbc.query(
          """
          SELECT appointment_id, service_name_snapshot, starts_at, service_ends_at,
                 time_zone, status, version
          FROM agendamento.appointment
          WHERE tenant_id = ? AND client_id = ?
            AND (? = FALSE OR starts_at >= ?)
            AND (CAST(? AS timestamptz) IS NULL OR (starts_at, appointment_id) > (?, ?))
          ORDER BY starts_at ASC, appointment_id ASC
          LIMIT ?
          """,
          (row, index) ->
              new ClientAppointmentView(
                  row.getObject("appointment_id", UUID.class),
                  row.getString("service_name_snapshot"),
                  row.getTimestamp("starts_at").toInstant(),
                  row.getTimestamp("service_ends_at").toInstant(),
                  row.getString("time_zone"),
                  row.getString("status"),
                  row.getLong("version")),
          tenantId,
          clientId,
          futureOnly,
          Timestamp.from(now),
          afterStartsAt == null ? null : Timestamp.from(afterStartsAt),
          afterStartsAt == null ? null : Timestamp.from(afterStartsAt),
          afterAppointmentId,
          limit);
    } catch (DataAccessException exception) {
      throw new AppointmentDataUnavailableException(exception);
    }
  }

  @Override
  public Optional<ClientAppointmentView> findClientAppointment(
      UUID tenantId, UUID clientId, UUID appointmentId) {
    try {
      return jdbc
          .query(
              """
              SELECT appointment_id, service_name_snapshot, starts_at, service_ends_at,
                     time_zone, status, version
              FROM agendamento.appointment
              WHERE tenant_id = ? AND client_id = ? AND appointment_id = ?
              """,
              (row, index) ->
                  new ClientAppointmentView(
                      row.getObject("appointment_id", UUID.class),
                      row.getString("service_name_snapshot"),
                      row.getTimestamp("starts_at").toInstant(),
                      row.getTimestamp("service_ends_at").toInstant(),
                      row.getString("time_zone"),
                      row.getString("status"),
                      row.getLong("version")),
              tenantId,
              clientId,
              appointmentId)
          .stream()
          .findFirst();
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
  public Optional<Appointment> findForReschedule(UUID tenantId, UUID appointmentId) {
    try {
      return jdbc
          .query(
              """
              SELECT appointment_id, tenant_id, client_id, service_id, starts_at, service_ends_at,
                     occupied_until, time_zone, client_name_snapshot, service_name_snapshot,
                     service_duration_minutes, service_buffer_after_minutes,
                     service_price_minor_snapshot, service_currency_snapshot, status, created_at, version
              FROM agendamento.appointment
              WHERE tenant_id = ? AND appointment_id = ?
              FOR UPDATE
              """,
              (row, index) ->
                  new Appointment(
                      row.getObject("appointment_id", UUID.class),
                      row.getObject("tenant_id", UUID.class),
                      row.getObject("client_id", UUID.class),
                      row.getObject("service_id", UUID.class),
                      row.getTimestamp("starts_at").toInstant(),
                      row.getTimestamp("service_ends_at").toInstant(),
                      row.getTimestamp("occupied_until").toInstant(),
                      row.getString("time_zone"),
                      row.getString("client_name_snapshot"),
                      row.getString("service_name_snapshot"),
                      row.getInt("service_duration_minutes"),
                      row.getInt("service_buffer_after_minutes"),
                      (Long) row.getObject("service_price_minor_snapshot"),
                      row.getString("service_currency_snapshot"),
                      AppointmentStatus.valueOf(row.getString("status")),
                      row.getTimestamp("created_at").toInstant(),
                      row.getLong("version")),
              tenantId,
              appointmentId)
          .stream()
          .findFirst();
    } catch (DataAccessException exception) {
      throw new AppointmentDataUnavailableException(exception);
    }
  }

  @Override
  public boolean reschedule(
      Appointment current,
      long expectedVersion,
      com.appagendamento.appointments.api.AdministrativeBookingAssessment assessment,
      String reason) {
    try {
      int updated =
          jdbc.update(
              """
              UPDATE agendamento.appointment
              SET starts_at = ?, service_ends_at = ?, occupied_until = ?, version = version + 1
              WHERE tenant_id = ? AND appointment_id = ? AND status = 'CONFIRMED' AND version = ?
              """,
              Timestamp.from(assessment.startsAt()),
              Timestamp.from(assessment.serviceEndsAt()),
              Timestamp.from(assessment.occupiedUntil()),
              current.tenantId(),
              current.appointmentId(),
              expectedVersion);
      if (updated != 1) return false;
      jdbc.update(
          """
          INSERT INTO agendamento.appointment_history (
            history_id, tenant_id, appointment_id, from_status, to_status,
            actor_type, actor_ref, reason_code, reason, occurred_at,
            previous_starts_at, previous_occupied_until, new_starts_at, new_occupied_until,
            version_before, version_after
          ) VALUES (?, ?, ?, 'CONFIRMED', 'CONFIRMED', 'ADMIN', 'TENANT_ADMIN',
                    'APPOINTMENT_RESCHEDULED', ?, clock_timestamp(), ?, ?, ?, ?, ?, ?)
          """,
          UUID.randomUUID(),
          current.tenantId(),
          current.appointmentId(),
          reason,
          Timestamp.from(current.startsAt()),
          Timestamp.from(current.occupiedUntil()),
          Timestamp.from(assessment.startsAt()),
          Timestamp.from(assessment.occupiedUntil()),
          expectedVersion,
          expectedVersion + 1);
      return true;
    } catch (DataIntegrityViolationException exception) {
      if (isAppointmentExclusionViolation(exception)) {
        throw new com.appagendamento.appointments.domain.SlotNoLongerAvailableException();
      }
      throw exception;
    } catch (DataAccessException exception) {
      throw new AppointmentDataUnavailableException(exception);
    }
  }

  @Override
  public boolean transitionOutcome(
      Appointment current,
      long expectedVersion,
      com.appagendamento.appointments.domain.AppointmentOutcome outcome,
      String reason) {
    try {
      int updated =
          jdbc.update(
              """
              UPDATE agendamento.appointment
              SET status = ?, version = version + 1
              WHERE tenant_id = ? AND appointment_id = ? AND status = 'CONFIRMED' AND version = ?
              """,
              outcome.status().name(),
              current.tenantId(),
              current.appointmentId(),
              expectedVersion);
      if (updated != 1) return false;
      jdbc.update(
          """
          INSERT INTO agendamento.appointment_history (
            history_id, tenant_id, appointment_id, from_status, to_status,
            actor_type, actor_ref, reason_code, reason, occurred_at,
            previous_starts_at, previous_occupied_until, new_starts_at, new_occupied_until,
            version_before, version_after
          ) VALUES (?, ?, ?, 'CONFIRMED', ?, 'ADMIN', 'TENANT_ADMIN', ?, ?, clock_timestamp(),
                    NULL, NULL, NULL, NULL, ?, ?)
          """,
          UUID.randomUUID(),
          current.tenantId(),
          current.appointmentId(),
          outcome.status().name(),
          outcome.historyReason(),
          reason,
          expectedVersion,
          expectedVersion + 1);
      return true;
    } catch (DataAccessException exception) {
      throw new AppointmentDataUnavailableException(exception);
    }
  }

  @Override
  public boolean correctNoShow(Appointment current, long expectedVersion, String reason) {
    try {
      UUID originalHistoryId =
          jdbc.queryForObject(
              """
              SELECT history_id FROM agendamento.appointment_history
              WHERE tenant_id = ? AND appointment_id = ? AND to_status = 'NO_SHOW'
                AND reason_code = 'APPOINTMENT_NO_SHOW' AND corrects_history_id IS NULL
              ORDER BY occurred_at DESC, history_id DESC
              LIMIT 1
              """,
              UUID.class,
              current.tenantId(),
              current.appointmentId());
      int updated =
          jdbc.update(
              """
              UPDATE agendamento.appointment
              SET status = 'COMPLETED', version = version + 1
              WHERE tenant_id = ? AND appointment_id = ? AND status = 'NO_SHOW' AND version = ?
              """,
              current.tenantId(),
              current.appointmentId(),
              expectedVersion);
      if (updated != 1) return false;
      jdbc.update(
          """
          INSERT INTO agendamento.appointment_history (
            history_id, tenant_id, appointment_id, from_status, to_status,
            actor_type, actor_ref, reason_code, reason, occurred_at,
            version_before, version_after, corrects_history_id
          ) VALUES (?, ?, ?, 'NO_SHOW', 'COMPLETED', 'ADMIN', 'TENANT_ADMIN',
                    'APPOINTMENT_NO_SHOW_CORRECTED', ?, clock_timestamp(), ?, ?, ?)
          """,
          UUID.randomUUID(),
          current.tenantId(),
          current.appointmentId(),
          reason,
          expectedVersion,
          expectedVersion + 1,
          originalHistoryId);
      return true;
    } catch (DataAccessException exception) {
      throw new AppointmentDataUnavailableException(exception);
    }
  }

  @Override
  public boolean transitionClientCancellation(
      Appointment current, UUID clientId, long expectedVersion) {
    try {
      int updated =
          jdbc.update(
              """
              UPDATE agendamento.appointment
              SET status = 'CANCELLED_BY_CLIENT', version = version + 1
              WHERE tenant_id = ? AND client_id = ? AND appointment_id = ?
                AND status = 'CONFIRMED' AND version = ?
              """,
              current.tenantId(),
              clientId,
              current.appointmentId(),
              expectedVersion);
      if (updated != 1) return false;
      jdbc.update(
          """
          INSERT INTO agendamento.appointment_history (
            history_id, tenant_id, appointment_id, from_status, to_status,
            actor_type, actor_ref, reason_code, reason, occurred_at,
            previous_starts_at, previous_occupied_until, new_starts_at, new_occupied_until,
            version_before, version_after
          ) VALUES (?, ?, ?, 'CONFIRMED', 'CANCELLED_BY_CLIENT', 'CLIENT', 'VERIFIED_CLIENT',
                    'APPOINTMENT_CANCELLED_BY_CLIENT', NULL, clock_timestamp(),
                    NULL, NULL, NULL, NULL, ?, ?)
          """,
          UUID.randomUUID(),
          current.tenantId(),
          current.appointmentId(),
          expectedVersion,
          expectedVersion + 1);
      return true;
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
          SELECT from_status, to_status, actor_type, actor_ref, reason_code, reason, occurred_at,
                 previous_starts_at, previous_occupied_until, new_starts_at, new_occupied_until,
                 version_before, version_after, corrects_history_id
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
                  row.getString("reason"),
                  row.getTimestamp("occurred_at").toInstant(),
                  row.getTimestamp("previous_starts_at") == null
                      ? null
                      : row.getTimestamp("previous_starts_at").toInstant(),
                  row.getTimestamp("previous_occupied_until") == null
                      ? null
                      : row.getTimestamp("previous_occupied_until").toInstant(),
                  row.getTimestamp("new_starts_at") == null
                      ? null
                      : row.getTimestamp("new_starts_at").toInstant(),
                  row.getTimestamp("new_occupied_until") == null
                      ? null
                      : row.getTimestamp("new_occupied_until").toInstant(),
                  (Long) row.getObject("version_before"),
                  (Long) row.getObject("version_after"),
                  row.getObject("corrects_history_id", UUID.class)),
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
