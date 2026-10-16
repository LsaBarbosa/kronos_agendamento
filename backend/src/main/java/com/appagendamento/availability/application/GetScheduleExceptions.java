package com.appagendamento.availability.application;

import com.appagendamento.availability.domain.ScheduleAccessDeniedException;
import com.appagendamento.availability.domain.ScheduleDateRange;
import com.appagendamento.availability.domain.ScheduleException;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GetScheduleExceptions {
  private final ScheduleRepository schedules;
  private final AdminAccessPolicy accessPolicy;

  public GetScheduleExceptions(ScheduleRepository schedules, AdminAccessPolicy accessPolicy) {
    this.schedules = schedules;
    this.accessPolicy = accessPolicy;
  }

  public Query execute(AuthenticatedAdmin actor, LocalDate from, LocalDate to) {
    if (!accessPolicy.isCurrent(actor)) throw new ScheduleAccessDeniedException();
    ScheduleDateRange range = new ScheduleDateRange(from, to);
    return new Query(
        range.from(),
        range.to(),
        schedules.findTenantTimeZone(actor.tenantId()),
        schedules.findScheduleExceptions(actor.tenantId(), from, to));
  }

  public record Query(
      LocalDate from, LocalDate to, String timeZone, List<ScheduleException> exceptions) {}
}
