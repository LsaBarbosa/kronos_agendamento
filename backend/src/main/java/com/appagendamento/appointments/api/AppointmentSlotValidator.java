package com.appagendamento.appointments.api;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentSlotValidator {

  Optional<ValidatedAppointmentSlot> findCurrentSlot(
      UUID tenantId, UUID serviceId, OffsetDateTime requestedStart);
}
