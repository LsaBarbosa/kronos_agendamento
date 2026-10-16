package com.appagendamento.appointments.api;

import java.time.Instant;
import java.util.UUID;

public interface ConfirmedAppointmentConflictProbe {

  boolean overlapsConfirmed(UUID tenantId, Instant startsAt, Instant endsAt);
}
