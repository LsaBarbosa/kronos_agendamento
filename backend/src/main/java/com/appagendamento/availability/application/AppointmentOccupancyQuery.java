package com.appagendamento.availability.application;

import com.appagendamento.availability.domain.OccupiedInterval;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AppointmentOccupancyQuery {

  List<OccupiedInterval> findOverlapping(UUID tenantId, Instant startsAt, Instant endsAt);
}
