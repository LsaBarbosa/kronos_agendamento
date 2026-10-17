package com.appagendamento.appointments.application;

import com.appagendamento.appointments.domain.AdminAppointmentAccessDeniedException;
import com.appagendamento.appointments.domain.AdminAppointmentNotFoundException;
import com.appagendamento.appointments.domain.AppointmentDataUnavailableException;
import com.appagendamento.appointments.domain.AppointmentStatus;
import com.appagendamento.appointments.domain.InvalidAdminAgendaQueryException;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetAdminAppointments {

  private static final int MAX_DATES = 366;
  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 100;

  private final AppointmentRepository appointments;
  private final AdminAccessPolicy accessPolicy;

  public GetAdminAppointments(AppointmentRepository appointments, AdminAccessPolicy accessPolicy) {
    this.appointments = appointments;
    this.accessPolicy = accessPolicy;
  }

  @Transactional(readOnly = true)
  public AdminAppointmentPage list(
      AuthenticatedAdmin actor,
      LocalDate from,
      LocalDate to,
      String status,
      UUID serviceId,
      String cursor,
      Integer requestedLimit) {
    requireCurrent(actor);
    if (from == null || to == null || from.isAfter(to))
      throw new InvalidAdminAgendaQueryException();
    try {
      if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > MAX_DATES) {
        throw new InvalidAdminAgendaQueryException();
      }
      if (requestedLimit != null && (requestedLimit < 1 || requestedLimit > MAX_LIMIT)) {
        throw new InvalidAdminAgendaQueryException();
      }
      if (status != null) AppointmentStatus.valueOf(status);
      var zone = ZoneId.of(appointments.findTenantTimeZone(actor.tenantId()));
      var windowStart = from.atStartOfDay(zone).toInstant();
      var windowEnd = to.plusDays(1).atStartOfDay(zone).toInstant();
      var position = Cursor.decode(cursor);
      int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
      List<AdminAppointmentView> fetched =
          appointments.findAdminAppointments(
              actor.tenantId(),
              windowStart,
              windowEnd,
              status,
              serviceId,
              position == null ? null : position.startsAt(),
              position == null ? null : position.appointmentId(),
              limit + 1);
      boolean hasMore = fetched.size() > limit;
      List<AdminAppointmentView> items =
          List.copyOf(fetched.subList(0, Math.min(limit, fetched.size())));
      String nextCursor = hasMore && !items.isEmpty() ? Cursor.encode(items.getLast()) : null;
      return new AdminAppointmentPage(zone.getId(), items, nextCursor, hasMore);
    } catch (InvalidAdminAgendaQueryException exception) {
      throw exception;
    } catch (IllegalArgumentException | java.time.DateTimeException exception) {
      throw new InvalidAdminAgendaQueryException();
    } catch (AppointmentDataUnavailableException exception) {
      throw exception;
    }
  }

  @Transactional(readOnly = true)
  public AdminAppointmentView detail(AuthenticatedAdmin actor, UUID appointmentId) {
    requireCurrent(actor);
    return appointments
        .findAdminAppointment(actor.tenantId(), appointmentId)
        .orElseThrow(AdminAppointmentNotFoundException::new);
  }

  @Transactional(readOnly = true)
  public AdminAppointmentHistory history(AuthenticatedAdmin actor, UUID appointmentId) {
    requireCurrent(actor);
    AdminAppointmentView appointment =
        appointments
            .findAdminAppointment(actor.tenantId(), appointmentId)
            .orElseThrow(AdminAppointmentNotFoundException::new);
    return new AdminAppointmentHistory(
        appointment.timeZone(),
        appointments.findAdminAppointmentHistory(actor.tenantId(), appointmentId));
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new AdminAppointmentAccessDeniedException();
    }
  }

  private record Cursor(java.time.Instant startsAt, UUID appointmentId) {

    static Cursor decode(String encoded) {
      if (encoded == null || encoded.isBlank()) return null;
      try {
        if (encoded.length() > 512) throw new IllegalArgumentException();
        String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        String[] fields = value.split("\\|", -1);
        if (fields.length != 2) throw new IllegalArgumentException();
        return new Cursor(java.time.Instant.parse(fields[0]), UUID.fromString(fields[1]));
      } catch (RuntimeException exception) {
        throw new InvalidAdminAgendaQueryException();
      }
    }

    static String encode(AdminAppointmentView appointment) {
      String value = appointment.startsAt() + "|" + appointment.appointmentId();
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
  }
}
