package com.appagendamento.availability.api;

import com.appagendamento.availability.application.GetBlockedPeriods;
import com.appagendamento.availability.application.GetScheduleExceptions;
import com.appagendamento.availability.application.ManageBlockedPeriods;
import com.appagendamento.availability.application.ManageScheduleExceptions;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScheduleExceptionMutationController {
  private final GetScheduleExceptions getExceptions;
  private final GetBlockedPeriods getBlocks;
  private final ManageScheduleExceptions manageExceptions;
  private final ManageBlockedPeriods manageBlocks;

  public ScheduleExceptionMutationController(
      GetScheduleExceptions getExceptions,
      GetBlockedPeriods getBlocks,
      ManageScheduleExceptions manageExceptions,
      ManageBlockedPeriods manageBlocks) {
    this.getExceptions = getExceptions;
    this.getBlocks = getBlocks;
    this.manageExceptions = manageExceptions;
    this.manageBlocks = manageBlocks;
  }

  @GetMapping("/api/v1/admin/schedule/exceptions")
  ResponseEntity<ScheduleExceptionListResponse> exceptions(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @RequestParam LocalDate from,
      @RequestParam LocalDate to) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ScheduleExceptionListResponse.from(getExceptions.execute(actor, from, to)));
  }

  @PostMapping("/api/v1/admin/schedule/exceptions")
  ResponseEntity<ScheduleExceptionResponse> createException(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody CreateScheduleExceptionRequest request) {
    var result =
        manageExceptions.create(
            actor,
            key,
            request.localDate(),
            request.kind(),
            request.periods().stream().map(SpecialPeriodRequest::toDomain).toList(),
            request.reason());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(ScheduleExceptionResponse.from(result.value()));
  }

  @PatchMapping("/api/v1/admin/schedule/exceptions/{id}")
  ResponseEntity<ScheduleExceptionResponse> updateException(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody SaveScheduleExceptionRequest request) {
    var result =
        manageExceptions.update(
            actor,
            key,
            id,
            request.expectedVersion(),
            request.localDate(),
            request.kind(),
            request.periods().stream().map(SpecialPeriodRequest::toDomain).toList(),
            request.reason());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(ScheduleExceptionResponse.from(result.value()));
  }

  @DeleteMapping("/api/v1/admin/schedule/exceptions/{id}")
  ResponseEntity<ManageScheduleExceptions.ExceptionRemovalReceipt> removeException(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody RemoveScheduleExceptionRequest request) {
    var result = manageExceptions.remove(actor, key, id, request.expectedVersion());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(result.value());
  }

  @GetMapping("/api/v1/admin/blocked-periods")
  ResponseEntity<List<BlockedPeriodResponse>> blocks(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @RequestParam LocalDate from,
      @RequestParam LocalDate to) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            getBlocks.execute(actor, from, to).stream().map(BlockedPeriodResponse::from).toList());
  }

  @PostMapping("/api/v1/admin/blocked-periods")
  ResponseEntity<BlockedPeriodResponse> createBlock(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody CreateBlockedPeriodRequest request) {
    var result =
        manageBlocks.create(actor, key, request.startsAt(), request.endsAt(), request.reason());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(BlockedPeriodResponse.from(result.value()));
  }

  @DeleteMapping("/api/v1/admin/blocked-periods/{id}")
  ResponseEntity<BlockedPeriodResponse> cancelBlock(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody CancelBlockedPeriodRequest request) {
    var result = manageBlocks.cancel(actor, key, id, request.expectedVersion(), request.reason());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(BlockedPeriodResponse.from(result.value()));
  }
}
