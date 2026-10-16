package com.appagendamento.availability.api;

import com.appagendamento.availability.application.GetScheduleConfiguration;
import com.appagendamento.availability.application.ReplaceAvailabilityPolicy;
import com.appagendamento.availability.application.ReplaceWeeklySchedule;
import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminScheduleController {

  private final GetScheduleConfiguration getConfiguration;
  private final ReplaceWeeklySchedule replaceWeeklySchedule;
  private final ReplaceAvailabilityPolicy replaceAvailabilityPolicy;

  public AdminScheduleController(
      GetScheduleConfiguration getConfiguration,
      ReplaceWeeklySchedule replaceWeeklySchedule,
      ReplaceAvailabilityPolicy replaceAvailabilityPolicy) {
    this.getConfiguration = getConfiguration;
    this.replaceWeeklySchedule = replaceWeeklySchedule;
    this.replaceAvailabilityPolicy = replaceAvailabilityPolicy;
  }

  @GetMapping("/api/v1/admin/schedule/weekly-periods")
  ResponseEntity<WeeklyScheduleResponse> weeklySchedule(
      @AuthenticationPrincipal AuthenticatedAdmin admin) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(WeeklyScheduleResponse.from(getConfiguration.weeklySchedule(admin)));
  }

  @PutMapping("/api/v1/admin/schedule/weekly-periods")
  ResponseEntity<WeeklyScheduleResponse> replaceWeeklySchedule(
      @AuthenticationPrincipal AuthenticatedAdmin admin,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody ReplaceWeeklyScheduleRequest request) {
    List<WeeklyPeriod> periods =
        request.periods().stream().map(WeeklyPeriodRequest::toDomain).toList();
    var result =
        replaceWeeklySchedule.execute(admin, idempotencyKey, request.expectedVersion(), periods);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(WeeklyScheduleResponse.from(result.value()));
  }

  @GetMapping("/api/v1/admin/availability-policy")
  ResponseEntity<AvailabilityPolicyResponse> availabilityPolicy(
      @AuthenticationPrincipal AuthenticatedAdmin admin) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            getConfiguration
                .availabilityPolicy(admin)
                .map(AvailabilityPolicyResponse::from)
                .orElseGet(AvailabilityPolicyResponse::absent));
  }

  @PutMapping("/api/v1/admin/availability-policy")
  ResponseEntity<AvailabilityPolicyResponse> replaceAvailabilityPolicy(
      @AuthenticationPrincipal AuthenticatedAdmin admin,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody ReplaceAvailabilityPolicyRequest request) {
    var result =
        replaceAvailabilityPolicy.execute(
            admin,
            idempotencyKey,
            request.expectedVersion(),
            request.slotStepMinutes(),
            request.minimumNoticeHours(),
            request.bookingHorizonDays(),
            request.cancellationCutoffHours(),
            request.reminderOffsetHours());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(AvailabilityPolicyResponse.from(result.value()));
  }
}
