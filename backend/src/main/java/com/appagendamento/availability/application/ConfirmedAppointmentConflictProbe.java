package com.appagendamento.availability.application;

import java.time.Instant;
import java.util.UUID;

public interface ConfirmedAppointmentConflictProbe {

  boolean overlapsConfirmed(UUID tenantId, Instant startsAt, Instant endsAt);
}
