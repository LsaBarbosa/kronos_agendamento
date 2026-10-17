package com.appagendamento.availability.infrastructure;

import com.appagendamento.appointments.api.AgendaRevisionAdvancer;
import com.appagendamento.availability.application.ScheduleRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class AvailabilityAgendaRevisionAdvancer implements AgendaRevisionAdvancer {

  private final ScheduleRepository schedules;

  AvailabilityAgendaRevisionAdvancer(ScheduleRepository schedules) {
    this.schedules = schedules;
  }

  @Override
  public long advance(UUID tenantId) {
    return schedules.nextScheduleRevision(tenantId);
  }
}
