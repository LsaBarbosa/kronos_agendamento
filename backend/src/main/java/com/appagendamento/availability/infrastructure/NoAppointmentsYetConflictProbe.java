package com.appagendamento.availability.infrastructure;

import com.appagendamento.availability.application.ConfirmedAppointmentConflictProbe;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class NoAppointmentsYetConflictProbe implements ConfirmedAppointmentConflictProbe {

  @Override
  public boolean overlapsConfirmed(UUID tenantId, Instant startsAt, Instant endsAt) {
    return false;
  }
}
