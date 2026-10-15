package com.appagendamento.catalog.api;

import com.appagendamento.catalog.application.CreateService;
import com.appagendamento.catalog.application.GetService;
import com.appagendamento.catalog.application.ListServices;
import com.appagendamento.catalog.application.SetServiceStatus;
import com.appagendamento.catalog.application.UpdateService;
import com.appagendamento.catalog.domain.ServiceStatus;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/services")
public class AdminServiceController {

  private final CreateService createService;
  private final UpdateService updateService;
  private final SetServiceStatus setServiceStatus;
  private final GetService getService;
  private final ListServices listServices;

  public AdminServiceController(
      CreateService createService,
      UpdateService updateService,
      SetServiceStatus setServiceStatus,
      GetService getService,
      ListServices listServices) {
    this.createService = createService;
    this.updateService = updateService;
    this.setServiceStatus = setServiceStatus;
    this.getService = getService;
    this.listServices = listServices;
  }

  @GetMapping
  ResponseEntity<List<ServiceResponse>> list(@AuthenticationPrincipal AuthenticatedAdmin admin) {
    List<ServiceResponse> response =
        listServices.execute(admin).stream().map(ServiceResponse::from).toList();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
  }

  @GetMapping("/{serviceId}")
  ResponseEntity<ServiceResponse> get(
      @AuthenticationPrincipal AuthenticatedAdmin admin, @PathVariable UUID serviceId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ServiceResponse.from(getService.execute(admin, serviceId)));
  }

  @PostMapping
  ResponseEntity<ServiceResponse> create(
      @AuthenticationPrincipal AuthenticatedAdmin admin,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CreateServiceRequest request) {
    var result =
        createService.execute(
            admin,
            idempotencyKey,
            request.name(),
            request.description(),
            request.durationMinutes(),
            request.bufferAfterMinutes(),
            request.status());
    return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(ServiceResponse.from(result.value()));
  }

  @PatchMapping("/{serviceId}")
  ResponseEntity<ServiceResponse> update(
      @AuthenticationPrincipal AuthenticatedAdmin admin,
      @PathVariable UUID serviceId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody UpdateServiceRequest request) {
    var result =
        updateService.execute(
            admin,
            serviceId,
            idempotencyKey,
            request.expectedVersion(),
            request.name(),
            request.description(),
            request.durationMinutes(),
            request.bufferAfterMinutes());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(ServiceResponse.from(result.value()));
  }

  @PostMapping("/{serviceId}/activate")
  ResponseEntity<ServiceResponse> activate(
      @AuthenticationPrincipal AuthenticatedAdmin admin,
      @PathVariable UUID serviceId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody ServiceVersionRequest request) {
    return changeStatus(
        admin, serviceId, idempotencyKey, request.expectedVersion(), ServiceStatus.ACTIVE);
  }

  @PostMapping("/{serviceId}/deactivate")
  ResponseEntity<ServiceResponse> deactivate(
      @AuthenticationPrincipal AuthenticatedAdmin admin,
      @PathVariable UUID serviceId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody ServiceVersionRequest request) {
    return changeStatus(
        admin, serviceId, idempotencyKey, request.expectedVersion(), ServiceStatus.INACTIVE);
  }

  private ResponseEntity<ServiceResponse> changeStatus(
      AuthenticatedAdmin admin,
      UUID serviceId,
      String idempotencyKey,
      long expectedVersion,
      ServiceStatus target) {
    var result =
        setServiceStatus.execute(admin, serviceId, idempotencyKey, expectedVersion, target);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(ServiceResponse.from(result.value()));
  }
}
