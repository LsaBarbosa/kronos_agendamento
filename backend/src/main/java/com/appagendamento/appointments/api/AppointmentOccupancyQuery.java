package com.appagendamento.appointments.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AppointmentOccupancyQuery {

  List<AppointmentOccupancy> findOverlapping(UUID tenantId, Instant startsAt, Instant endsAt);
}
