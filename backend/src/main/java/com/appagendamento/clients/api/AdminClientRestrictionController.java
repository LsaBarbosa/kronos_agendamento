package com.appagendamento.clients.api;

import com.appagendamento.clients.application.ClientRestrictionLiftReceipt;
import com.appagendamento.clients.application.LiftClientBookingRestriction;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminClientRestrictionController {

  private final LiftClientBookingRestriction liftRestriction;

  public AdminClientRestrictionController(LiftClientBookingRestriction liftRestriction) {
    this.liftRestriction = liftRestriction;
  }

  @PostMapping("/api/v1/admin/clients/{id}/restrictions/{restrictionId}/lift")
  ResponseEntity<ClientRestrictionLiftReceipt> lift(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @PathVariable UUID id,
      @PathVariable UUID restrictionId,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
      @Valid @RequestBody LiftRequest request) {
    var result =
        liftRestriction.execute(
            actor, key, id, restrictionId, request.expectedVersion(), request.reason());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(result.value());
  }

  record LiftRequest(
      @NotNull @Positive Long expectedVersion, @NotBlank @Size(max = 500) String reason) {}
}
