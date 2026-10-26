package com.appagendamento.appointments.api;

import java.time.Instant;
import java.util.UUID;

/** Read-only, minimized facts for the tenant retention assessment. */
public interface AppointmentRetentionFactsQuery {

  Facts query(UUID tenantId, Instant referenceAt);

  record Count(int value, boolean atLeastLimit) {}

  record Facts(Count appointments, Count futureConfirmedAppointments) {}
}
