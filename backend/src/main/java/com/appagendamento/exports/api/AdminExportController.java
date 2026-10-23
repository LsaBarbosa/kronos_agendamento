package com.appagendamento.exports.api;

import com.appagendamento.appointments.domain.AppointmentStatus;
import com.appagendamento.exports.application.DownloadExport;
import com.appagendamento.exports.application.ExpireExport;
import com.appagendamento.exports.application.GetExport;
import com.appagendamento.exports.application.IssueExportDownloadHandle;
import com.appagendamento.exports.application.RequestExport;
import com.appagendamento.exports.domain.ExportFilters;
import com.appagendamento.exports.domain.ExportFormat;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminExportController {

  private final RequestExport requestExport;
  private final GetExport getExport;
  private final IssueExportDownloadHandle issueHandle;
  private final DownloadExport downloadExport;
  private final ExpireExport expireExport;

  public AdminExportController(
      RequestExport requestExport,
      GetExport getExport,
      IssueExportDownloadHandle issueHandle,
      DownloadExport downloadExport,
      ExpireExport expireExport) {
    this.requestExport = requestExport;
    this.getExport = getExport;
    this.issueHandle = issueHandle;
    this.downloadExport = downloadExport;
    this.expireExport = expireExport;
  }

  @PostMapping("/api/v1/admin/exports")
  ResponseEntity<ExportJobResponse> request(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String key,
      @Valid @RequestBody ExportRequestBody request) {
    var filters = toFilters(request.filters());
    var result =
        requestExport.execute(
            actor, key, ExportFormat.valueOf(request.format().name()), request.category(), filters);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/admin/exports/" + result.job().exportId()))
        .header("Cache-Control", "no-store")
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(ExportJobResponse.from(result.job()));
  }

  @GetMapping("/api/v1/admin/exports/{exportId}")
  ResponseEntity<ExportJobResponse> get(
      @AuthenticationPrincipal AuthenticatedAdmin actor, @PathVariable UUID exportId) {
    var job = getExport.execute(actor, exportId);
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .eTag('"' + Long.toString(job.version()) + '"')
        .body(ExportJobResponse.from(job));
  }

  @PostMapping("/api/v1/admin/exports/{exportId}/download-token")
  ResponseEntity<DownloadHandleResponse> issueHandle(
      @AuthenticationPrincipal AuthenticatedAdmin actor, @PathVariable UUID exportId) {
    var result = issueHandle.execute(actor, exportId);
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(new DownloadHandleResponse(result.downloadHandle(), result.expiresAt()));
  }

  @GetMapping("/api/v1/admin/exports/{exportId}/content")
  ResponseEntity<byte[]> download(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @PathVariable UUID exportId,
      @RequestHeader("X-Export-Download-Handle") @Size(min = 32, max = 128) String handle) {
    var content = downloadExport.execute(actor, exportId, handle);
    String extension = content.format() == ExportFormat.CSV ? "csv" : "pdf";
    MediaType mediaType =
        content.format() == ExportFormat.CSV
            ? new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8)
            : MediaType.APPLICATION_PDF;
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store, private")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=appointments-" + exportId + "." + extension)
        .eTag('"' + content.digest() + '"')
        .contentType(mediaType)
        .body(content.bytes());
  }

  @DeleteMapping("/api/v1/admin/exports/{exportId}")
  ResponseEntity<Void> expire(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @PathVariable UUID exportId,
      @RequestHeader("If-Match") String ifMatch) {
    long expectedVersion = parseVersion(ifMatch);
    expireExport.execute(actor, exportId, expectedVersion);
    return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
  }

  private static ExportFilters toFilters(ExportRequestBody.Filters filters) {
    if (filters == null)
      throw new com.appagendamento.exports.domain.InvalidExportRequestException();
    var statuses = new HashSet<AppointmentStatus>();
    if (filters.statuses() != null) {
      for (String status : filters.statuses()) {
        AppointmentStatus parsed;
        try {
          parsed = status == null ? null : AppointmentStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
          throw new com.appagendamento.exports.domain.InvalidExportRequestException();
        }
        if (parsed == null || !statuses.add(parsed)) {
          throw new com.appagendamento.exports.domain.InvalidExportRequestException();
        }
      }
    }
    return new ExportFilters(filters.from(), filters.to(), statuses, filters.serviceId());
  }

  private static long parseVersion(String header) {
    try {
      String value = header.trim();
      if (value.startsWith("\"")) value = value.substring(1, value.length() - 1);
      long parsed = Long.parseLong(value);
      if (parsed < 1) throw new NumberFormatException();
      return parsed;
    } catch (RuntimeException exception) {
      throw new com.appagendamento.exports.domain.InvalidExportRequestException();
    }
  }
}
