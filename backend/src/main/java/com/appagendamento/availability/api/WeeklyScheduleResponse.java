package com.appagendamento.availability.api;

import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.availability.domain.WeeklySchedule;
import java.util.List;

public record WeeklyScheduleResponse(
    boolean configured,
    String timeZone,
    long version,
    long scheduleRevision,
    List<WeeklyPeriodRequest> periods) {

  public static WeeklyScheduleResponse from(WeeklySchedule schedule, long scheduleRevision) {
    return new WeeklyScheduleResponse(
        schedule.configured(),
        schedule.timeZone(),
        schedule.version(),
        scheduleRevision,
        schedule.periods().stream().map(WeeklyScheduleResponse::from).toList());
  }

  private static WeeklyPeriodRequest from(WeeklyPeriod period) {
    return new WeeklyPeriodRequest(
        period.weekday(), period.localStart().toString(), period.localEnd().toString());
  }
}
