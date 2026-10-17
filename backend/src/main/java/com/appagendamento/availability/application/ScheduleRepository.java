package com.appagendamento.availability.application;

import com.appagendamento.availability.domain.AvailabilityPolicy;
import com.appagendamento.availability.domain.BlockedPeriod;
import com.appagendamento.availability.domain.ScheduleException;
import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.availability.domain.WeeklySchedule;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleRepository {

  WeeklySchedule findWeeklySchedule(UUID tenantId);

  Optional<AvailabilityPolicy> findAvailabilityPolicy(UUID tenantId);

  String findTenantTimeZone(UUID tenantId);

  long nextScheduleRevision(UUID tenantId);

  long currentScheduleRevision(UUID tenantId);

  boolean lockScheduleRevision(UUID tenantId, long expectedRevision);

  List<ScheduleException> findScheduleExceptions(UUID tenantId, LocalDate from, LocalDate to);

  Optional<ScheduleException> findScheduleException(UUID tenantId, UUID exceptionId);

  boolean insertScheduleException(ScheduleException scheduleException);

  boolean lockScheduleExceptionVersion(UUID tenantId, UUID exceptionId, long expectedVersion);

  boolean updateScheduleException(ScheduleException scheduleException, long expectedVersion);

  boolean deleteScheduleException(UUID tenantId, UUID exceptionId, long expectedVersion);

  List<BlockedPeriod> findBlockedPeriods(UUID tenantId, Instant from, Instant to);

  Optional<BlockedPeriod> findBlockedPeriod(UUID tenantId, UUID blockId);

  BlockedPeriod insertBlockedPeriod(BlockedPeriod blockedPeriod);

  boolean cancelBlockedPeriod(BlockedPeriod blockedPeriod, long expectedVersion);

  boolean lockWeeklyScheduleVersion(UUID tenantId, long expectedVersion);

  boolean lockAvailabilityPolicyVersion(UUID tenantId, long expectedVersion);

  boolean replaceWeeklySchedule(UUID tenantId, long expectedVersion, List<WeeklyPeriod> periods);

  boolean replaceAvailabilityPolicy(AvailabilityPolicy policy, long expectedVersion);
}
