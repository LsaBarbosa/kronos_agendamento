package com.appagendamento.appointments.application;

import com.appagendamento.appointments.domain.Appointment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository {

  void insertConfirmed(Appointment appointment, String operationId, String traceId);

  default void insertConfirmed(
      Appointment appointment,
      String operationId,
      String traceId,
      AppointmentCreationContext context) {
    insertConfirmed(appointment, operationId, traceId);
  }

  String findTenantTimeZone(UUID tenantId);

  List<AdminAppointmentView> findAdminAppointments(
      UUID tenantId,
      Instant windowStart,
      Instant windowEnd,
      String status,
      UUID serviceId,
      Instant afterStartsAt,
      UUID afterAppointmentId,
      int limit);

  Optional<AdminAppointmentView> findAdminAppointment(UUID tenantId, UUID appointmentId);

  Optional<Appointment> findForReschedule(UUID tenantId, UUID appointmentId);

  boolean reschedule(
      Appointment current,
      long expectedVersion,
      com.appagendamento.appointments.api.AdministrativeBookingAssessment assessment,
      String reason);

  boolean transitionOutcome(
      Appointment current,
      long expectedVersion,
      com.appagendamento.appointments.domain.AppointmentOutcome outcome,
      String reason);

  List<AppointmentHistoryEntry> findAdminAppointmentHistory(UUID tenantId, UUID appointmentId);
}
