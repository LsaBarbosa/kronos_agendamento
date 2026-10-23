package com.appagendamento.exports.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.exports.domain.ExportFilters;
import com.appagendamento.exports.domain.ExportFormat;
import com.appagendamento.exports.domain.ExportJobView;
import com.appagendamento.exports.domain.ExportUnavailableException;
import com.appagendamento.exports.domain.InvalidExportRequestException;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class RequestExport {

  private static final String SCOPE = "admin.export.v1";
  private static final String CATEGORY = "APPOINTMENTS";
  private final AdminAccessPolicy accessPolicy;
  private final IdempotencyCoordinator idempotency;
  private final ExportRepository exports;
  private final ExportSnapshotQuery snapshots;
  private final ExportCryptography cryptography;
  private final ExportArtifactStore artifactStore;
  private final ExportConfiguration configuration;
  private final ExpireExports expirations;
  private final AuditEventRecorder audit;
  private final OutboxEventRecorder outbox;
  private final AuditCorrelationId correlationId;
  private final Clock clock;
  private final ObjectMapper mapper;
  private final CommandResultCodec<ExportRequestReceipt> codec;

  public RequestExport(
      AdminAccessPolicy accessPolicy,
      IdempotencyCoordinator idempotency,
      ExportRepository exports,
      ExportSnapshotQuery snapshots,
      ExportCryptography cryptography,
      ExportArtifactStore artifactStore,
      ExportConfiguration configuration,
      ExpireExports expirations,
      AuditEventRecorder audit,
      OutboxEventRecorder outbox,
      AuditCorrelationId correlationId,
      Clock clock,
      ObjectMapper mapper) {
    this.accessPolicy = accessPolicy;
    this.idempotency = idempotency;
    this.exports = exports;
    this.snapshots = snapshots;
    this.cryptography = cryptography;
    this.artifactStore = artifactStore;
    this.configuration = configuration;
    this.expirations = expirations;
    this.audit = audit;
    this.outbox = outbox;
    this.correlationId = correlationId;
    this.clock = clock;
    this.mapper = mapper;
    this.codec =
        new CommandResultCodec<>() {
          @Override
          public String encode(ExportRequestReceipt result) {
            return mapper.writeValueAsString(result);
          }

          @Override
          public ExportRequestReceipt decode(String json) {
            return mapper.readValue(json, ExportRequestReceipt.class);
          }
        };
  }

  public ExportRequestResult execute(
      AuthenticatedAdmin actor,
      String idempotencyKey,
      ExportFormat format,
      String category,
      ExportFilters filters) {
    requireCurrent(actor);
    validate(format, category, filters);
    var digest = new ExportCommandDigest(format, category, filters);
    var result =
        idempotency.execute(
            actor.tenantId(),
            SCOPE,
            idempotencyKey,
            digest,
            () -> requireCurrent(actor),
            operationId -> create(actor, operationId, format, filters),
            codec);
    ExportJobView current =
        exports
            .find(actor.tenantId(), result.value().exportId())
            .orElseThrow(com.appagendamento.exports.domain.ExportNotFoundException::new);
    return new ExportRequestResult(result.operationId(), current, result.replayed());
  }

  private ExportRequestReceipt create(
      AuthenticatedAdmin actor, UUID operationId, ExportFormat format, ExportFilters filters) {
    if (!cryptography.available() || !artifactStore.available() || !configuration.enabled()) {
      throw new ExportUnavailableException();
    }
    java.time.Instant snapshotAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    expirations.expireDue(actor.tenantId(), snapshotAt, 50);
    UUID exportId = UUID.randomUUID();
    var snapshot = snapshots.capture(actor.tenantId(), filters);
    if (snapshot.rows().size() > configuration.maximumRows()) {
      throw new InvalidExportRequestException();
    }
    byte[] plain = mapper.writeValueAsBytes(snapshot);
    if (plain.length > configuration.maximumSnapshotBytes()) {
      throw new InvalidExportRequestException();
    }
    var encrypted =
        cryptography.encrypt(
            plain, "EXPORT_SNAPSHOT", actor.tenantId().toString(), exportId.toString());
    String digest = sha256(plain);
    ExportJobView view =
        exports.request(
            exportId,
            actor.tenantId(),
            actor.adminUserId(),
            operationId,
            format,
            filters,
            snapshotAt,
            encrypted.keyId(),
            digest,
            encrypted.ciphertext(),
            snapshot.rows().size());
    UUID traceId = correlationId.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            actor.adminUserId(),
            "ADMIN",
            "TENANT_ADMIN",
            "EXPORT_REQUESTED",
            "EXPORT",
            view.exportId(),
            "COMMITTED",
            UUID.randomUUID(),
            traceId,
            "{\"changed_fields\":[\"export\"],\"category\":\"APPOINTMENTS\",\"format\":\""
                + format.name()
                + "\",\"version_before\":0,\"version_after\":1}"));
    outbox.record(
        new OutboxEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            "EXPORT",
            view.exportId(),
            view.version(),
            "ExportRequested",
            1,
            operationId,
            traceId,
            "{\"exportId\":\"" + view.exportId() + "\",\"version\":1}"));
    return new ExportRequestReceipt(view.exportId());
  }

  private void validate(ExportFormat format, String category, ExportFilters filters) {
    if (format == null
        || !CATEGORY.equals(category)
        || filters == null
        || ChronoUnit.DAYS.between(filters.from(), filters.to()) + 1 > 366) {
      throw new InvalidExportRequestException();
    }
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new com.appagendamento.exports.domain.ExportAuthorizationDeniedException();
    }
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
