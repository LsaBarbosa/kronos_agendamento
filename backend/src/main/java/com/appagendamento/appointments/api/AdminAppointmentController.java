package com.appagendamento.appointments.api;

import com.appagendamento.appointments.application.GetAdminAppointments;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminAppointmentController {

  private final GetAdminAppointments appointments;

  public AdminAppointmentController(GetAdminAppointments appointments) {
    this.appointments = appointments;
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
