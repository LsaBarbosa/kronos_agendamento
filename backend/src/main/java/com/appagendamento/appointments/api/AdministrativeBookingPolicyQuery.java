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
}
