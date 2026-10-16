package com.appagendamento.availability.application;

import com.appagendamento.availability.domain.BlockedPeriod;
import com.appagendamento.availability.domain.ScheduleAccessDeniedException;
import com.appagendamento.availability.domain.ScheduleDateRange;
import com.appagendamento.availability.domain.ScheduleException;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GetBlockedPeriods {
  private final ScheduleRepository schedules;
  private final AdminAccessPolicy accessPolicy;

  public GetBlockedPeriods(ScheduleRepository schedules, AdminAccessPolicy accessPolicy) {
    this.schedules = schedules;
    this.accessPolicy = accessPolicy;
  }

  public List<BlockedPeriod> execute(AuthenticatedAdmin actor, LocalDate from, LocalDate to) {
    if (!accessPolicy.isCurrent(actor)) throw new ScheduleAccessDeniedException();
    new ScheduleDateRange(from, to);
    var zone = ScheduleException.zone(schedules.findTenantTimeZone(actor.tenantId()));
    return schedules.findBlockedPeriods(
        actor.tenantId(),
        from.atStartOfDay(zone).toInstant(),
        to.plusDays(1).atStartOfDay(zone).toInstant());
  }
}
