package com.appagendamento.publicpage.api;

import com.appagendamento.appointments.application.CreatePublicAppointment;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicAppointmentController {

  private final CreatePublicAppointment createAppointment;

  public PublicAppointmentController(CreatePublicAppointment createAppointment) {
    this.createAppointment = createAppointment;
  }

  @PostMapping("/api/v1/public/{slug}/appointments")
  ResponseEntity<PublicAppointmentResponse> create(
      @PathVariable String slug,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody PublicAppointmentRequest request) {
    var result =
        createAppointment.execute(
            slug,
            key,
            request.serviceId(),
            request.startsAt(),
            request.client().name(),
            request.client().phone());
    return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(PublicAppointmentResponse.from(result.value()));
  }
}
