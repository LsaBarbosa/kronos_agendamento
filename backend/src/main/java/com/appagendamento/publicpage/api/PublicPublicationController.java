package com.appagendamento.publicpage.api;

import com.appagendamento.availability.application.CalculateAvailabilityForDateRange;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.publicpage.application.ChangePublicationStatus;
import com.appagendamento.publicpage.application.GetPublicBusiness;
import com.appagendamento.publicpage.application.GetPublicService;
import com.appagendamento.publicpage.application.GetPublicationStatus;
import com.appagendamento.publicpage.application.ListPublicServices;
import com.appagendamento.publicpage.application.UpdatePublicContact;
import com.appagendamento.publicpage.domain.PublicBusiness;
import com.appagendamento.publicpage.domain.PublicService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicPublicationController {

  private final GetPublicationStatus getStatus;
  private final ChangePublicationStatus changeStatus;
  private final UpdatePublicContact updateContact;
  private final GetPublicBusiness getBusiness;
  private final ListPublicServices listServices;
  private final GetPublicService getService;
  private final CalculateAvailabilityForDateRange calculateAvailability;
  private final MeterRegistry meters;

  public PublicPublicationController(
      GetPublicationStatus getStatus,
      ChangePublicationStatus changeStatus,
      UpdatePublicContact updateContact,
      GetPublicBusiness getBusiness,
      ListPublicServices listServices,
      GetPublicService getService,
      CalculateAvailabilityForDateRange calculateAvailability,
      MeterRegistry meters) {
    this.getStatus = getStatus;
    this.changeStatus = changeStatus;
    this.updateContact = updateContact;
    this.getBusiness = getBusiness;
    this.listServices = listServices;
    this.getService = getService;
    this.calculateAvailability = calculateAvailability;
    this.meters = meters;
  }

  @GetMapping("/api/v1/admin/publication")
  ResponseEntity<AdminPublicationResponse> status(
      @AuthenticationPrincipal AuthenticatedAdmin admin) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(AdminPublicationResponse.from(getStatus.execute(admin)));
  }

  @PutMapping("/api/v1/admin/publication")
  ResponseEntity<AdminPublicationResponse> change(
      @AuthenticationPrincipal AuthenticatedAdmin admin,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody PublicationStatusRequest request) {
    var result = changeStatus.execute(admin, key, request.published(), request.expectedVersion());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(AdminPublicationResponse.from(getStatus.execute(admin).withState(result.value())));
  }

  @PutMapping("/api/v1/admin/public-profile")
  ResponseEntity<AdminPublicationResponse> updateContact(
      @AuthenticationPrincipal AuthenticatedAdmin admin,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody PublicContactRequest request) {
    var result =
        updateContact.execute(admin, key, request.contactPhone(), request.expectedVersion());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(AdminPublicationResponse.from(getStatus.execute(admin).withState(result.value())));
  }

  @GetMapping("/api/v1/public/{slug}")
  ResponseEntity<PublicBusinessResponse> business(@PathVariable String slug) {
    PublicBusiness business = getBusiness.execute(slug);
    meters.counter("agd.public.page.view").increment();
    return publicResponse(PublicBusinessResponse.from(business));
  }

  @GetMapping("/api/v1/public/{slug}/services")
  ResponseEntity<List<PublicServiceResponse>> services(@PathVariable String slug) {
    List<PublicServiceResponse> services =
        listServices.execute(slug).stream().map(PublicServiceResponse::from).toList();
    return publicResponse(services);
  }

  @GetMapping("/api/v1/public/{slug}/services/{serviceId}")
  ResponseEntity<PublicServiceResponse> service(
      @PathVariable String slug, @PathVariable UUID serviceId) {
    PublicService service = getService.execute(slug, serviceId);
    return publicResponse(PublicServiceResponse.from(service));
  }

  @GetMapping("/api/v1/public/{slug}/availability")
  ResponseEntity<?> availability(
      @PathVariable String slug,
      @RequestParam UUID serviceId,
      @RequestParam LocalDate from,
      @RequestParam LocalDate to) {
    PublicBusiness business = getBusiness.execute(slug);
    return publicResponse(calculateAvailability.execute(business.tenantId(), serviceId, from, to));
  }

  @PostMapping("/api/v1/public/{slug}/telemetry")
  ResponseEntity<Void> telemetry(
      @PathVariable String slug, @Valid @RequestBody PublicTelemetryRequest request) {
    getBusiness.execute(slug);
    String meter =
        switch (request.event() == null ? "" : request.event()) {
          case "SERVICE_SELECTED" -> "agd.public.service.selected";
          case "SLOT_SELECTED" -> "agd.public.slot.selected";
          case "FLOW_ABANDONED" -> "agd.public.flow.abandoned";
          default -> throw new IllegalArgumentException("Evento não permitido");
        };
    meters.counter(meter).increment();
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  private static <T> ResponseEntity<T> publicResponse(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
