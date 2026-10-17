package com.appagendamento.availability.application;

import com.appagendamento.availability.domain.AvailabilityPolicy;
import com.appagendamento.availability.domain.ScheduleAccessDeniedException;
import com.appagendamento.availability.domain.WeeklySchedule;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class GetScheduleConfiguration {

  private final ScheduleRepository schedules;
  private final AdminAccessPolicy accessPolicy;

  public GetScheduleConfiguration(ScheduleRepository schedules, AdminAccessPolicy accessPolicy) {
    this.schedules = schedules;
    this.accessPolicy = accessPolicy;
  }

  public WeeklySchedule weeklySchedule(AuthenticatedAdmin actor) {
    requireCurrent(actor);
    return schedules.findWeeklySchedule(actor.tenantId());
  }

  public Optional<AvailabilityPolicy> availabilityPolicy(AuthenticatedAdmin actor) {
    requireCurrent(actor);
    return schedules.findAvailabilityPolicy(actor.tenantId());
  }

  public long scheduleRevision(AuthenticatedAdmin actor) {
    requireCurrent(actor);
    return schedules.currentScheduleRevision(actor.tenantId());
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (!accessPolicy.isCurrent(actor)) {
      throw new ScheduleAccessDeniedException();
    }
  }
}
