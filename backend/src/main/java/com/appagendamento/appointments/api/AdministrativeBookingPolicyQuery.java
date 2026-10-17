package com.appagendamento.appointments.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface AdministrativeBookingPolicyQuery {

  AdministrativeBookingAssessment assess(
      UUID tenantId,
      UUID serviceId,
      OffsetDateTime requestedStart,
      long expectedScheduleRevision,
      boolean allowOutsideWeeklySchedule,
      boolean allowMinimumNotice);

  default AdministrativeBookingAssessment assessReschedule(
      UUID tenantId,
      UUID appointmentId,
      UUID serviceId,
      int durationMinutes,
      int bufferAfterMinutes,
      OffsetDateTime requestedStart,
      long expectedScheduleRevision,
      boolean allowOutsideWeeklySchedule,
      boolean allowMinimumNotice) {
    return assess(
        tenantId,
        serviceId,
        requestedStart,
        expectedScheduleRevision,
        allowOutsideWeeklySchedule,
        allowMinimumNotice);
  }
}
