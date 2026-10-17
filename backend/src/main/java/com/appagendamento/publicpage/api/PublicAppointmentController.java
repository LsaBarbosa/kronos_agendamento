package com.appagendamento.publicpage.api;

import com.appagendamento.appointments.application.CreatePublicAppointment;
import com.appagendamento.appointments.application.GetPublicBookingIntent;
import com.appagendamento.appointments.application.PreparePublicBookingIntent;
import com.appagendamento.appointments.application.PublicBookingRecoveryState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicAppointmentController {

  private final CreatePublicAppointment createAppointment;
  private final PreparePublicBookingIntent prepareIntent;
  private final GetPublicBookingIntent getIntent;

  public PublicAppointmentController(
      CreatePublicAppointment createAppointment,
      PreparePublicBookingIntent prepareIntent,
      GetPublicBookingIntent getIntent) {
    this.createAppointment = createAppointment;
    this.prepareIntent = prepareIntent;
    this.getIntent = getIntent;
  }

  @PostMapping("/api/v1/public/{slug}/booking-intents/current")
  ResponseEntity<Void> prepare(
      @PathVariable String slug,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
      @RequestHeader("X-Booking-Recovery") String recoveryCapability,
      @Valid @RequestBody PublicAppointmentRequest request) {
    prepareIntent.execute(
        slug,
        key,
        recoveryCapability,
        request.serviceId(),
        request.startsAt(),
        request.client().name(),
        request.client().phone());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  @GetMapping("/api/v1/public/{slug}/booking-intents/current")
  ResponseEntity<PublicBookingRecoveryState> recover(
      @PathVariable String slug, @RequestHeader("X-Booking-Recovery") String recoveryCapability) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(getIntent.execute(slug, recoveryCapability));
  }

  @PostMapping("/api/v1/public/{slug}/appointments")
  ResponseEntity<PublicAppointmentResponse> create(
      @PathVariable String slug,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
      @RequestHeader("X-Booking-Recovery") String recoveryCapability,
      @Valid @RequestBody PublicAppointmentRequest request) {
    var result =
        createAppointment.execute(
            slug,
            key,
            request.serviceId(),
            request.startsAt(),
            request.client().name(),
            request.client().phone(),
            recoveryCapability);
    return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(PublicAppointmentResponse.from(result.value()));
  }
}
