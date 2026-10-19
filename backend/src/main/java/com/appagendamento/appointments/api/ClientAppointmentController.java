package com.appagendamento.appointments.api;

import com.appagendamento.appointments.application.CancelClientAppointment;
import com.appagendamento.appointments.application.ClientAppointmentCancellationCommand;
import com.appagendamento.appointments.application.GetClientAppointments;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClientAppointmentController {

  private final GetClientAppointments appointments;
  private final CancelClientAppointment cancelAppointment;

  public ClientAppointmentController(
      GetClientAppointments appointments, CancelClientAppointment cancelAppointment) {
    this.appointments = appointments;
    this.cancelAppointment = cancelAppointment;
  }

  @GetMapping("/api/v1/client/appointments")
  ResponseEntity<ClientAppointmentPageResponse> list(
      @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ClientAppointmentPageResponse.from(appointments.list(cookie, status, cursor)));
  }

  @GetMapping("/api/v1/client/appointments/{appointmentId}")
  ResponseEntity<ClientAppointmentResponse> detail(
      @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
      @PathVariable UUID appointmentId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ClientAppointmentResponse.from(appointments.detail(cookie, appointmentId)));
  }

  @PostMapping("/api/v1/client/appointments/{appointmentId}/cancel")
  ResponseEntity<ClientAppointmentCancellationResponse> cancel(
      @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
      @PathVariable UUID appointmentId,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
      @Valid @RequestBody CancelClientAppointmentRequest request) {
    var result =
        cancelAppointment.execute(
            cookie,
            key,
            new ClientAppointmentCancellationCommand(appointmentId, request.expectedVersion()));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(ClientAppointmentCancellationResponse.from(result.value()));
  }
}
