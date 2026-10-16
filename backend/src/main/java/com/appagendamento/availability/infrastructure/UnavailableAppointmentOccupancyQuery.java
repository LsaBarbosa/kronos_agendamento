package com.appagendamento.availability.infrastructure;

import com.appagendamento.availability.application.AppointmentOccupancyQuery;
import com.appagendamento.availability.application.AppointmentOccupancyUnavailableException;
import com.appagendamento.availability.domain.OccupiedInterval;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!e2e")
public class UnavailableAppointmentOccupancyQuery implements AppointmentOccupancyQuery {

  @Override
  public List<OccupiedInterval> findOverlapping(UUID tenantId, Instant startsAt, Instant endsAt) {
    throw new AppointmentOccupancyUnavailableException();
  }
}
