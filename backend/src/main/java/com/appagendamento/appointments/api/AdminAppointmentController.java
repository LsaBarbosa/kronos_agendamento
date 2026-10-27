package com.appagendamento.appointments.api;

import com.appagendamento.appointments.application.CorrectAdministrativeNoShow;
import com.appagendamento.appointments.application.CreateAdministrativeAppointment;
import com.appagendamento.appointments.application.GetAdminAppointments;
import com.appagendamento.appointments.application.RescheduleAdministrativeAppointment;
import com.appagendamento.appointments.application.TransitionAdministrativeAppointment;
import com.appagendamento.appointments.domain.AppointmentOutcome;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminAppointmentController {

  private final GetAdminAppointments appointments;
  private final CreateAdministrativeAppointment createAppointment;
  private final RescheduleAdministrativeAppointment rescheduleAppointment;
  private final TransitionAdministrativeAppointment transitionAppointment;
  private final CorrectAdministrativeNoShow correctNoShow;
  private final BookingMetrics bookingMetrics;

  public AdminAppointmentController(
      GetAdminAppointments appointments,
      CreateAdministrativeAppointment createAppointment,
      RescheduleAdministrativeAppointment rescheduleAppointment,
      TransitionAdministrativeAppointment transitionAppointment,
      CorrectAdministrativeNoShow correctNoShow,
      BookingMetrics bookingMetrics) {
    this.appointments = appointments;
    this.createAppointment = createAppointment;
    this.rescheduleAppointment = rescheduleAppointment;
    this.transitionAppointment = transitionAppointment;
    this.correctNoShow = correctNoShow;
    this.bookingMetrics = bookingMetrics;
  }

  @PostMapping("/api/v1/admin/appointments/{id}/correct-no-show")
  ResponseEntity<AdministrativeAppointmentOutcomeResponse> correctNoShow(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
      @Valid @RequestBody CorrectNoShowRequest request) {
    bookingMetrics.attempt("admin");
    var result = correctNoShow.execute(actor, key, id, request.expectedVersion(), request.reason());
    bookingMetrics.created("admin", result.replayed(), "outcome_recorded");
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(AdministrativeAppointmentOutcomeResponse.from(result.value()));
  }

  record CorrectNoShowRequest(
      @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive
          Long expectedVersion,
      @jakarta.validation.constraints.NotBlank @Size(max = 500) String reason) {}

  @PostMapping("/api/v1/admin/appointments/{id}/cancel")
  ResponseEntity<AdministrativeAppointmentOutcomeResponse> cancel(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
      @Valid @RequestBody AdministrativeAppointmentOutcomeRequest request) {
    return transition(actor, id, key, request, AppointmentOutcome.CANCEL_BY_PROVIDER);
  }

  @PostMapping("/api/v1/admin/appointments/{id}/complete")
  ResponseEntity<AdministrativeAppointmentOutcomeResponse> complete(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
      @Valid @RequestBody AdministrativeAppointmentOutcomeRequest request) {
    return transition(actor, id, key, request, AppointmentOutcome.COMPLETE);
  }

  @PostMapping("/api/v1/admin/appointments/{id}/mark-no-show")
  ResponseEntity<AdministrativeAppointmentOutcomeResponse> markNoShow(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
      @Valid @RequestBody AdministrativeAppointmentOutcomeRequest request) {
    return transition(actor, id, key, request, AppointmentOutcome.MARK_NO_SHOW);
  }

  private ResponseEntity<AdministrativeAppointmentOutcomeResponse> transition(
      AuthenticatedAdmin actor,
      UUID id,
      String key,
      AdministrativeAppointmentOutcomeRequest request,
      AppointmentOutcome outcome) {
    bookingMetrics.attempt("admin");
    var result = transitionAppointment.execute(actor, key, outcome, request.toCommand(id));
    bookingMetrics.created(
        "admin",
        result.replayed(),
        outcome == AppointmentOutcome.CANCEL_BY_PROVIDER ? "cancelled" : "outcome_recorded");
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(AdministrativeAppointmentOutcomeResponse.from(result.value()));
  }

  @PostMapping("/api/v1/admin/appointments/{id}/reschedule")
  ResponseEntity<AdministrativeRescheduleResponse> reschedule(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
      @Valid @RequestBody RescheduleAdministrativeAppointmentRequest request) {
    bookingMetrics.attempt("admin");
    var result = rescheduleAppointment.execute(actor, key, request.toCommand(id));
    bookingMetrics.created("admin", result.replayed(), "rescheduled");
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(AdministrativeRescheduleResponse.from(result.value()));
  }

  @PostMapping("/api/v1/admin/appointments")
  ResponseEntity<AdministrativeAppointmentResponse> create(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
      @Valid @RequestBody CreateAdministrativeAppointmentRequest request) {
    bookingMetrics.attempt("admin");
    var result = createAppointment.execute(actor, key, request.toCommand());
    bookingMetrics.created("admin", result.replayed(), "created");
    return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(AdministrativeAppointmentResponse.from(result.value()));
  }

  @GetMapping("/api/v1/admin/appointments")
  ResponseEntity<AdminAppointmentPageResponse> list(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @RequestParam LocalDate from,
      @RequestParam LocalDate to,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID serviceId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            AdminAppointmentPageResponse.from(
                appointments.list(actor, from, to, status, serviceId, cursor, limit)));
  }

  @GetMapping("/api/v1/admin/appointments/{id}")
  ResponseEntity<AdminAppointmentResponse> detail(
      @AuthenticationPrincipal AuthenticatedAdmin actor, @PathVariable UUID id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(AdminAppointmentResponse.from(appointments.detail(actor, id)));
  }

  @GetMapping("/api/v1/admin/appointments/{id}/history")
  ResponseEntity<AdminAppointmentHistoryResponse> history(
      @AuthenticationPrincipal AuthenticatedAdmin actor, @PathVariable UUID id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(AdminAppointmentHistoryResponse.from(appointments.history(actor, id)));
  }
}
