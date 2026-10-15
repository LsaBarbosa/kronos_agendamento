package com.appagendamento.availability.application;

import com.appagendamento.availability.domain.AvailabilityPolicy;
import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.availability.domain.WeeklySchedule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleRepository {

  WeeklySchedule findWeeklySchedule(UUID tenantId);

  Optional<AvailabilityPolicy> findAvailabilityPolicy(UUID tenantId);

  boolean lockWeeklyScheduleVersion(UUID tenantId, long expectedVersion);

  boolean lockAvailabilityPolicyVersion(UUID tenantId, long expectedVersion);

  boolean replaceWeeklySchedule(UUID tenantId, long expectedVersion, List<WeeklyPeriod> periods);

  boolean replaceAvailabilityPolicy(AvailabilityPolicy policy, long expectedVersion);
}
