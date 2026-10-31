package com.appagendamento.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.exports.application.DownloadExport;
import com.appagendamento.exports.application.ExpireExport;
import com.appagendamento.exports.application.ExportArtifactStore;
import com.appagendamento.exports.application.ExportConfiguration;
import com.appagendamento.exports.application.ExportWorker;
import com.appagendamento.exports.application.GetExport;
import com.appagendamento.exports.application.IssueExportDownloadHandle;
import com.appagendamento.exports.application.RequestExport;
import com.appagendamento.exports.domain.ExportAlreadyActiveException;
import com.appagendamento.exports.domain.ExportFilters;
import com.appagendamento.exports.domain.ExportFormat;
import com.appagendamento.exports.domain.ExportNotFoundException;
import com.appagendamento.exports.domain.ExportState;
import com.appagendamento.exports.domain.ExportUnavailableException;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import com.appagendamento.support.PostgresTestEnvironment;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "identity.oidc.enabled=true",
      "identity.oidc.issuer-uri=https://identity.test/realms/agendamento",
      "identity.oidc.authorization-uri=https://identity.test/realms/agendamento/protocol/openid-connect/auth",
      "identity.oidc.token-uri=https://identity.test/realms/agendamento/protocol/openid-connect/token",
      "identity.oidc.jwk-set-uri=https://identity.test/realms/agendamento/protocol/openid-connect/certs",
      "identity.oidc.user-info-uri=https://identity.test/realms/agendamento/protocol/openid-connect/userinfo",
      "identity.oidc.client-id=integration-test",
      "identity.oidc.client-secret=not-a-real-secret",
      "identity.oidc.redirect-uri={baseUrl}/login/oauth2/code/keycloak",
      "app.exports.enabled=true",
      "app.exports.maximum-rows=100",
      "app.exports.maximum-snapshot-bytes=1000000",
      "app.exports.encryption.active-key-id=test-export-v1",
      "app.exports.encryption.keys=test-export-v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "app.exports.worker.enabled=false",
      "app.subscriptions.dunning-worker.enabled=false",
      "app.subscriptions.trial-expiry-worker.enabled=false"
    })
@AutoConfigureMockMvc
class ExportLifecycleIT {

  private static final String ISSUER = "https://identity.test/realms/agendamento";
  private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
  private final Map<String, StoredArtifact> objects = new ConcurrentHashMap<>();
  private final Map<String, Long> tombstones = new ConcurrentHashMap<>();
  private final Map<String, Object> objectLocks = new ConcurrentHashMap<>();
  @Autowired private JdbcTemplate appJdbc;
  @Autowired private Flyway flyway;
  @Autowired private RequestExport requestExport;
  @Autowired private GetExport getExport;
  @Autowired private IssueExportDownloadHandle issueHandle;
  @Autowired private DownloadExport downloadExport;
  @Autowired private ExpireExport expireExport;
  @Autowired private ExportWorker worker;
  @Autowired private ExportConfiguration configuration;
  @Autowired private MockMvc mvc;
  @MockitoBean private ExportArtifactStore artifactStore;
  @MockitoBean private Clock clock;
  @MockitoSpyBean private AuditEventRecorder audit;
  @MockitoSpyBean private OutboxEventRecorder outbox;
  private JdbcTemplate migrationJdbc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void setUp() {
    migrationJdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
    migrationJdbc.update("DELETE FROM agendamento.export_download_handle");
    migrationJdbc.update("DELETE FROM agendamento.export_job");
    objects.clear();
    tombstones.clear();
    objectLocks.clear();
    reset(artifactStore, clock, audit, outbox);
    when(clock.instant()).thenReturn(NOW);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(artifactStore.available()).thenReturn(true);
    doAnswer(
            invocation -> {
              String key = invocation.getArgument(0);
              long fencingVersion = invocation.getArgument(1);
              byte[] body = invocation.getArgument(2);
              storeInMemory(key, fencingVersion, body);
              return null;
            })
        .when(artifactStore)
        .put(anyString(), anyLong(), any(byte[].class));
    when(artifactStore.get(anyString()))
        .thenAnswer(invocation -> objects.get(invocation.getArgument(0)).bytes().clone());
    doAnswer(
            invocation -> {
              String key = invocation.getArgument(0);
              long fence = invocation.getArgument(1);
              synchronized (objectLocks.computeIfAbsent(key, ignored -> new Object())) {
                tombstones.merge(key, fence, Math::max);
                objects.computeIfPresent(
                    key, (ignored, current) -> current.fencingVersion() <= fence ? null : current);
              }
              return null;
            })
        .when(artifactStore)
        .delete(anyString(), anyLong());
  }

  @Test
  void immutableSnapshotCsvDownloadExpiryAndAuditRemainTenantScoped() throws Exception {
    Fixture fixture = fixture("snapshot");
    String key = "export-request-" + UUID.randomUUID();
    var first =
        requestExport.execute(fixture.admin(), key, ExportFormat.CSV, "APPOINTMENTS", filters());

    assertThat(first.replayed()).isFalse();
    assertThat(first.job().state()).isEqualTo(ExportState.REQUESTED);
    assertThat(first.job().rowCount()).isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT encode(snapshot_ciphertext, 'escape') FROM agendamento.export_job WHERE export_id = ?",
                String.class,
                first.job().exportId()))
        .doesNotContain("private-client", "=SUM(");

    migrationJdbc.update(
        "UPDATE agendamento.appointment SET service_name_snapshot = 'Changed after snapshot', client_name_snapshot = 'changed-client' WHERE appointment_id = ?",
        fixture.appointmentId());
    processUntilAvailable(fixture, first.job().exportId());
    var replay =
        requestExport.execute(fixture.admin(), key, ExportFormat.CSV, "APPOINTMENTS", filters());
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.job().exportId()).isEqualTo(first.job().exportId());
    assertThat(replay.job().state()).isEqualTo(ExportState.AVAILABLE);
    assertThat(replay.job().version()).isEqualTo(3);

    var handle = issueHandle.execute(fixture.admin(), replay.job().exportId());
    var downloaded =
        downloadExport.execute(fixture.admin(), replay.job().exportId(), handle.downloadHandle());
    String csv = new String(downloaded.bytes(), StandardCharsets.UTF_8);
    assertThat(csv)
        .contains("'=SUM(1,1)", "America/Sao_Paulo", "CONFIRMED")
        .doesNotContain("Private Client Name", "private-client", "changed-client", "phone");
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.audit_event WHERE target_id = ? AND action = 'EXPORT_DOWNLOADED'",
                Integer.class,
                replay.job().exportId()))
        .isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.outbox_event WHERE aggregate_id = ? AND event_type = 'ExportAvailable'",
                Integer.class,
                replay.job().exportId()))
        .isEqualTo(1);

    Instant expiresAt = handle.expiresAt();
    when(clock.instant()).thenReturn(expiresAt);
    assertThatThrownBy(
            () ->
                downloadExport.execute(
                    fixture.admin(), replay.job().exportId(), handle.downloadHandle()))
        .isInstanceOf(com.appagendamento.exports.domain.ExportExpiredException.class);
    worker.expireAndClean();
    assertThat(getExport.execute(fixture.admin(), replay.job().exportId()).state())
        .isEqualTo(ExportState.EXPIRED);
    assertThat(objects).isEmpty();
  }

  @Test
  void generatedPdfRemainsReadableAfterPersistedJobProcessing() throws Exception {
    Fixture fixture = fixture("pdf");
    var requested =
        requestExport.execute(
            fixture.admin(),
            "pdf-export-" + UUID.randomUUID(),
            ExportFormat.PDF,
            "APPOINTMENTS",
            filters());
    processUntilAvailable(fixture, requested.job().exportId());
    var handle = issueHandle.execute(fixture.admin(), requested.job().exportId());
    var content =
        downloadExport.execute(
            fixture.admin(), requested.job().exportId(), handle.downloadHandle());
    String text;
    try (var pdf = Loader.loadPDF(content.bytes())) {
      text = new PDFTextStripper().getText(pdf);
    }
    assertThat(content.format()).isEqualTo(ExportFormat.PDF);
    assertThat(text).contains("Agenda", "=SUM(1,1)", "CONFIRMED", "buffer 15 min");
    assertThat(text).doesNotContain("Private Client Name", "+5511999991234");
  }

  @Test
  void dateFilterIncludesAppointmentWhoseOccupiedIntervalCrossesIntoTheLocalWindow() {
    Fixture fixture = fixture("window-intersection");
    UUID crossingAppointment = UUID.randomUUID();
    Instant startsAt = Instant.parse("2026-10-23T02:30:00Z");
    migrationJdbc.update(
        "INSERT INTO agendamento.appointment (appointment_id,tenant_id,client_id,service_id,starts_at,service_ends_at,occupied_until,time_zone,client_name_snapshot,service_name_snapshot,service_duration_minutes,service_buffer_after_minutes,status,created_at,version) VALUES (?,?,?,?,?,?,?,'America/Sao_Paulo','Private Client Name','Crossing snapshot',30,15,'CONFIRMED',?,1)",
        crossingAppointment,
        fixture.tenantId(),
        migrationJdbc.queryForObject(
            "SELECT client_id FROM agendamento.appointment WHERE appointment_id = ?",
            UUID.class,
            fixture.appointmentId()),
        fixture.serviceId(),
        java.sql.Timestamp.from(startsAt),
        java.sql.Timestamp.from(startsAt.plusSeconds(1800)),
        java.sql.Timestamp.from(startsAt.plusSeconds(2700)),
        java.sql.Timestamp.from(startsAt.minusSeconds(86400)));

    var export =
        requestExport.execute(
            fixture.admin(),
            "window-intersection-" + UUID.randomUUID(),
            ExportFormat.CSV,
            "APPOINTMENTS",
            filters());
    assertThat(export.job().rowCount()).isEqualTo(2);
    processUntilAvailable(fixture, export.job().exportId());
    var handle = issueHandle.execute(fixture.admin(), export.job().exportId());
    var csv =
        new String(
            downloadExport
                .execute(fixture.admin(), export.job().exportId(), handle.downloadHandle())
                .bytes(),
            StandardCharsets.UTF_8);
    assertThat(csv).contains(fixture.appointmentId().toString(), crossingAppointment.toString());
  }

  @Test
  void concurrentRequestsAllowOnlyOneActiveExportPerTenant() throws Exception {
    Fixture fixture = fixture("one-active");
    CyclicBarrier barrier = new CyclicBarrier(2);
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<Object> first =
          pool.submit(
              () ->
                  concurrentRequest(barrier, fixture.admin(), "export-key-A-" + UUID.randomUUID()));
      Future<Object> second =
          pool.submit(
              () ->
                  concurrentRequest(barrier, fixture.admin(), "export-key-B-" + UUID.randomUUID()));
      List<Object> results = List.of(first.get(), second.get());
      assertThat(
              results.stream()
                  .filter(
                      com.appagendamento.exports.application.ExportRequestResult.class::isInstance))
          .hasSize(1);
      assertThat(results.stream().filter(ExportAlreadyActiveException.class::isInstance))
          .hasSize(1);
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.export_job WHERE tenant_id = ? AND state IN ('REQUESTED','PROCESSING','RETRY_WAIT','AVAILABLE')",
                Integer.class,
                fixture.tenantId()))
        .isEqualTo(1);
  }

  @Test
  void availableJobParticipatesInUniqueTenantLimitAndRuntimeGrantsStayMinimal() {
    Fixture fixture = fixture("available-unique");
    var first =
        requestExport.execute(
            fixture.admin(),
            "available-one-" + UUID.randomUUID(),
            ExportFormat.CSV,
            "APPOINTMENTS",
            filters());
    assertThat(worker.processOne()).isTrue();
    migrationJdbc.update(
        "UPDATE agendamento.export_job SET state = 'EXPIRED' WHERE export_id = ?",
        first.job().exportId());
    var second =
        requestExport.execute(
            fixture.admin(),
            "available-two-" + UUID.randomUUID(),
            ExportFormat.CSV,
            "APPOINTMENTS",
            filters());
    assertThat(worker.processOne()).isTrue();

    assertThatThrownBy(
            () ->
                migrationJdbc.update(
                    "UPDATE agendamento.export_job SET state = 'AVAILABLE' WHERE export_id = ?",
                    first.job().exportId()))
        .hasMessageContaining("uq_export_one_active_per_tenant");
    assertThat(
            appJdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_runtime','agendamento.export_job','SELECT')",
                Boolean.class))
        .isTrue();
    assertThat(
            appJdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_runtime','agendamento.export_job','DELETE')",
                Boolean.class))
        .isFalse();
    assertThat(count("export_job", fixture.tenantId())).isEqualTo(2);
    assertThat(getExport.execute(fixture.admin(), second.job().exportId()).state())
        .isEqualTo(ExportState.AVAILABLE);
  }

  @Test
  void v43FreshInstallValidatesAndFlywayRerunIsIdempotent() {
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("43");
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'agendamento' AND indexname = 'uq_export_one_active_per_tenant'",
                String.class))
        .contains("'AVAILABLE'");
    assertThat(
            appJdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM agendamento.export_job WHERE state IN ('REQUESTED','PROCESSING','RETRY_WAIT','AVAILABLE') GROUP BY tenant_id HAVING count(*) > 1)",
                Boolean.class))
        .isFalse();
  }

  @Test
  void v42UpgradeFailsClosedOnLegacyAvailableAndRequestedJobsWithoutChangingRows() {
    String schema = "exports_v42_" + UUID.randomUUID().toString().replace("-", "");
    Flyway v41 = isolatedFlyway(schema, org.flywaydb.core.api.MigrationVersion.fromVersion("41"));
    assertThat(v41.migrate().migrationsExecuted).isEqualTo(41);
    JdbcTemplate isolated = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
    UUID tenantId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    UUID availableId = UUID.randomUUID();
    UUID requestedId = UUID.randomUUID();
    UUID availableOperation = UUID.randomUUID();
    UUID requestedOperation = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO "
            + schema
            + ".tenant(tenant_id,name,slug,timezone,status) VALUES (?, 'V42 legacy', ?, 'UTC', 'ACTIVE')",
        tenantId,
        "v42-" + tenantId.toString().substring(0, 8));
    migrationJdbc.update(
        "INSERT INTO "
            + schema
            + ".admin_user(user_id,identity_issuer,identity_subject) VALUES (?, ?, ?)",
        adminId,
        ISSUER,
        "v42-" + UUID.randomUUID());
    insertV42Idempotency(schema, tenantId, availableOperation);
    insertV42Idempotency(schema, tenantId, requestedOperation);
    migrationJdbc.update(
        "INSERT INTO "
            + schema
            + ".export_job(export_id,tenant_id,requested_by_admin_id,operation_id,format,category,filters,snapshot_at,snapshot_ciphertext,snapshot_key_id,snapshot_digest,row_count,state,attempt_count,next_attempt_at,artifact_key,artifact_key_id,artifact_digest,artifact_size,available_at,expires_at) "
            + "VALUES (?, ?, ?, ?, 'CSV', 'APPOINTMENTS', '{}'::jsonb, TIMESTAMPTZ '2026-10-23 12:00:00Z', decode(repeat('ab', 30),'hex'), 'test-v1', repeat('a',64), 0, 'AVAILABLE', 1, TIMESTAMPTZ '2026-10-23 12:00:00Z', ?, 'test-v1', repeat('b',64), 60, TIMESTAMPTZ '2026-10-23 12:00:00Z', TIMESTAMPTZ '2026-10-23 12:30:00Z')",
        availableId,
        tenantId,
        adminId,
        availableOperation,
        tenantId + "/exports/" + availableId);
    migrationJdbc.update(
        "INSERT INTO "
            + schema
            + ".export_job(export_id,tenant_id,requested_by_admin_id,operation_id,format,category,filters,snapshot_at,snapshot_ciphertext,snapshot_key_id,snapshot_digest,row_count,state,attempt_count,next_attempt_at,artifact_key) "
            + "VALUES (?, ?, ?, ?, 'CSV', 'APPOINTMENTS', '{}'::jsonb, TIMESTAMPTZ '2026-10-23 12:00:00Z', decode(repeat('cd', 30),'hex'), 'test-v1', repeat('c',64), 0, 'REQUESTED', 0, TIMESTAMPTZ '2026-10-23 12:00:00Z', ?)",
        requestedId,
        tenantId,
        adminId,
        requestedOperation,
        tenantId + "/exports/" + requestedId);

    Flyway upgrade = isolatedFlyway(schema);
    assertThatThrownBy(upgrade::migrate)
        .hasStackTraceContaining(
            "V42 cannot apply: multiple active export jobs exist for a tenant");
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("41");
    assertThat(
            isolated.queryForObject(
                "SELECT count(*) FROM " + schema + ".export_job WHERE tenant_id = ?",
                Integer.class,
                tenantId))
        .isEqualTo(2);
    assertThat(
            isolated.queryForObject(
                "SELECT state FROM " + schema + ".export_job WHERE export_id = ?",
                String.class,
                availableId))
        .isEqualTo("AVAILABLE");
    assertThat(
            isolated.queryForObject(
                "SELECT state FROM " + schema + ".export_job WHERE export_id = ?",
                String.class,
                requestedId))
        .isEqualTo("REQUESTED");
    assertThat(
            isolated.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND indexname = 'uq_export_one_active_per_tenant'",
                String.class,
                schema))
        .contains("'REQUESTED'", "'PROCESSING'", "'RETRY_WAIT'")
        .doesNotContain("'AVAILABLE'");
  }

  @Test
  void v41ToV43SuccessfulUpgradeKeepsSingleAvailableJobAndRuntimePrivilegesMinimal() {
    String schema = "exports_v42_ok_" + UUID.randomUUID().toString().replace("-", "");
    Flyway v41 = isolatedFlyway(schema, org.flywaydb.core.api.MigrationVersion.fromVersion("41"));
    assertThat(v41.migrate().migrationsExecuted).isEqualTo(41);
    UUID tenantId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    UUID exportId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO "
            + schema
            + ".tenant(tenant_id,name,slug,timezone,status) VALUES (?, 'V42 upgrade', ?, 'UTC', 'ACTIVE')",
        tenantId,
        "v42-ok-" + tenantId.toString().substring(0, 8));
    migrationJdbc.update(
        "INSERT INTO "
            + schema
            + ".admin_user(user_id,identity_issuer,identity_subject) VALUES (?, ?, ?)",
        adminId,
        ISSUER,
        "v42-ok-" + UUID.randomUUID());
    insertV42Idempotency(schema, tenantId, operationId);
    migrationJdbc.update(
        "INSERT INTO "
            + schema
            + ".export_job(export_id,tenant_id,requested_by_admin_id,operation_id,format,category,filters,snapshot_at,snapshot_ciphertext,snapshot_key_id,snapshot_digest,row_count,state,attempt_count,next_attempt_at,artifact_key,artifact_key_id,artifact_digest,artifact_size,available_at,expires_at) "
            + "VALUES (?, ?, ?, ?, 'CSV', 'APPOINTMENTS', '{}'::jsonb, TIMESTAMPTZ '2026-10-23 12:00:00Z', decode(repeat('ab', 30),'hex'), 'test-v1', repeat('a',64), 0, 'AVAILABLE', 1, TIMESTAMPTZ '2026-10-23 12:00:00Z', ?, 'test-v1', repeat('b',64), 60, TIMESTAMPTZ '2026-10-23 12:00:00Z', TIMESTAMPTZ '2026-10-23 12:30:00Z')",
        exportId,
        tenantId,
        adminId,
        operationId,
        tenantId + "/exports/" + exportId);

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("43");
    assertThat(
            migrationJdbc.queryForObject(
                "SELECT count(*) FROM "
                    + schema
                    + ".export_job WHERE tenant_id = ? AND state = 'AVAILABLE'",
                Integer.class,
                tenantId))
        .isEqualTo(1);
    assertThat(
            migrationJdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_runtime', ?, 'SELECT') AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE')",
                Boolean.class,
                schema + ".export_job",
                schema + ".export_job",
                schema + ".export_job"))
        .isTrue();
    assertThat(
            migrationJdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND indexname = 'uq_export_one_active_per_tenant'",
                String.class,
                schema))
        .contains("'AVAILABLE'");
  }

  private void insertV42Idempotency(String schema, UUID tenantId, UUID operationId) {
    migrationJdbc.update(
        "INSERT INTO "
            + schema
            + ".idempotency_record(tenant_id,command_scope,key_digest,request_digest,operation_id,state,result,completed_at) "
            + "VALUES (?, 'admin.export.v1', ?, ?, ?, 'COMPLETED', '{}'::jsonb, TIMESTAMPTZ '2026-10-23 12:00:00Z')",
        tenantId,
        digest(operationId + "key"),
        digest(operationId + "request"),
        operationId);
  }

  private static String digest(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private Flyway isolatedFlyway(String schema) {
    return isolatedFlyway(schema, org.flywaydb.core.api.MigrationVersion.LATEST);
  }

  private Flyway isolatedFlyway(String schema, org.flywaydb.core.api.MigrationVersion target) {
    return Flyway.configure()
        .dataSource(PostgresTestEnvironment.migrationDataSource())
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .createSchemas(true)
        .cleanDisabled(true)
        .load();
  }

  @Test
  void transientStorageFailureUsesScheduledRetryAndMonotonicArtifactFence() {
    Fixture fixture = fixture("retry");
    var requested =
        requestExport.execute(
            fixture.admin(),
            "retry-export-" + UUID.randomUUID(),
            ExportFormat.CSV,
            "APPOINTMENTS",
            filters());
    AtomicInteger sends = new AtomicInteger();
    java.util.List<Long> fencingVersions = new java.util.concurrent.CopyOnWriteArrayList<>();
    doAnswer(
            invocation -> {
              long fence = invocation.getArgument(1);
              fencingVersions.add(fence);
              if (sends.getAndIncrement() == 0) {
                throw new com.appagendamento.exports.application.ExportStorageException(true);
              }
              storeInMemory(invocation.getArgument(0), fence, invocation.getArgument(2));
              return null;
            })
        .when(artifactStore)
        .put(anyString(), anyLong(), any(byte[].class));

    assertThat(worker.processOne()).isTrue();
    var waiting = getExport.execute(fixture.admin(), requested.job().exportId());
    assertThat(waiting.state()).isEqualTo(ExportState.RETRY_WAIT);
    assertThat(waiting.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
    assertThat(worker.processOne()).isFalse();

    when(clock.instant()).thenReturn(NOW.plusSeconds(30));
    assertThat(worker.processOne()).isTrue();
    assertThat(getExport.execute(fixture.admin(), requested.job().exportId()).state())
        .isEqualTo(ExportState.AVAILABLE);
    assertThat(sends).hasValue(2);
    assertThat(fencingVersions).containsExactly(2L, 4L);
    assertThat(objects).hasSize(1);
  }

  @Test
  void temporarilyUnavailableStoreUsesRetryWithoutClaimingSuccess() {
    Fixture fixture = fixture("unavailable-store-retry");
    var requested =
        requestExport.execute(
            fixture.admin(),
            "unavailable-store-" + UUID.randomUUID(),
            ExportFormat.CSV,
            "APPOINTMENTS",
            filters());
    when(artifactStore.available()).thenReturn(false);

    assertThat(worker.processOne()).isTrue();
    var retry = getExport.execute(fixture.admin(), requested.job().exportId());
    assertThat(retry.state()).isEqualTo(ExportState.RETRY_WAIT);
    assertThat(retry.failureCode()).isEqualTo("STORAGE_TRANSIENT");
    assertThat(objects).isEmpty();
    assertThat(countAction("EXPORT_AVAILABLE", fixture.tenantId())).isZero();

    when(artifactStore.available()).thenReturn(true);
    when(clock.instant()).thenReturn(NOW.plusSeconds(30));
    assertThat(worker.processOne()).isTrue();
    assertThat(getExport.execute(fixture.admin(), requested.job().exportId()).state())
        .isEqualTo(ExportState.AVAILABLE);
  }

  @Test
  void terminalCleanupFencesLateWriteFromExpiredWorkerClaim() throws Exception {
    Fixture fixture = fixture("stale-worker-cleanup");
    var requested =
        requestExport.execute(
            fixture.admin(),
            "stale-worker-" + UUID.randomUUID(),
            ExportFormat.CSV,
            "APPOINTMENTS",
            filters());
    CountDownLatch firstPutEntered = new CountDownLatch(1);
    CountDownLatch releaseFirstPut = new CountDownLatch(1);
    AtomicInteger puts = new AtomicInteger();
    doAnswer(
            invocation -> {
              String key = invocation.getArgument(0);
              long fence = invocation.getArgument(1);
              byte[] bytes = invocation.getArgument(2);
              if (puts.getAndIncrement() == 0) {
                firstPutEntered.countDown();
                if (!releaseFirstPut.await(10, TimeUnit.SECONDS)) {
                  throw new AssertionError("timed out waiting to release the first worker put");
                }
                storeInMemory(key, fence, bytes);
                return null;
              }
              throw new com.appagendamento.exports.application.ExportStorageException(false);
            })
        .when(artifactStore)
        .put(anyString(), anyLong(), any(byte[].class));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Boolean> staleWorker = executor.submit(worker::processOne);
      assertThat(firstPutEntered.await(10, TimeUnit.SECONDS))
          .as("first worker must reach external storage while holding only a lease")
          .isTrue();

      when(clock.instant()).thenReturn(NOW.plusSeconds(61));
      assertThat(worker.processOne()).isTrue();
      assertThat(getExport.execute(fixture.admin(), requested.job().exportId()).state())
          .isEqualTo(ExportState.FAILED_FINAL);
      assertThat(worker.expireAndClean()).isEqualTo(1);
      assertThat(objects).isEmpty();
      assertThat(tombstones.values()).contains(4L);

      releaseFirstPut.countDown();
      assertThat(staleWorker.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(getExport.execute(fixture.admin(), requested.job().exportId()).state())
          .isEqualTo(ExportState.FAILED_FINAL);
      assertThat(objects).isEmpty();
      assertThat(
              appJdbc.queryForObject(
                  "SELECT artifact_deleted_at IS NOT NULL FROM agendamento.export_job WHERE export_id = ?",
                  Boolean.class,
                  requested.job().exportId()))
          .isTrue();
      assertThat(countAction("EXPORT_AVAILABLE", fixture.tenantId())).isZero();
    } finally {
      releaseFirstPut.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void transientRetriesUseThirtySixtyAndOneHundredTwentySecondBackoffThenStop() {
    Fixture fixture = fixture("retry-exhaustion");
    var requested =
        requestExport.execute(
            fixture.admin(),
            "retry-exhaustion-" + UUID.randomUUID(),
            ExportFormat.CSV,
            "APPOINTMENTS",
            filters());
    AtomicInteger sends = new AtomicInteger();
    doAnswer(
            invocation -> {
              sends.incrementAndGet();
              throw new com.appagendamento.exports.application.ExportStorageException(true);
            })
        .when(artifactStore)
        .put(anyString(), anyLong(), any(byte[].class));

    assertThat(worker.processOne()).isTrue();
    assertThat(getExport.execute(fixture.admin(), requested.job().exportId()).nextAttemptAt())
        .isEqualTo(NOW.plusSeconds(30));
    when(clock.instant()).thenReturn(NOW.plusSeconds(30));
    assertThat(worker.processOne()).isTrue();
    assertThat(getExport.execute(fixture.admin(), requested.job().exportId()).nextAttemptAt())
        .isEqualTo(NOW.plusSeconds(90));
    when(clock.instant()).thenReturn(NOW.plusSeconds(90));
    assertThat(worker.processOne()).isTrue();
    assertThat(getExport.execute(fixture.admin(), requested.job().exportId()).nextAttemptAt())
        .isEqualTo(NOW.plusSeconds(210));
    when(clock.instant()).thenReturn(NOW.plusSeconds(210));
    assertThat(worker.processOne()).isTrue();
    var finalState = getExport.execute(fixture.admin(), requested.job().exportId());
    assertThat(finalState.state()).isEqualTo(ExportState.FAILED_FINAL);
    assertThat(finalState.failureCode()).isEqualTo("STORAGE_TRANSIENT");
    assertThat(sends).hasValue(4);
  }

  @Test
  void definitiveStorageFailureDoesNotRetry() {
    Fixture fixture = fixture("definitive-storage");
    var requested =
        requestExport.execute(
            fixture.admin(),
            "definitive-storage-" + UUID.randomUUID(),
            ExportFormat.CSV,
            "APPOINTMENTS",
            filters());
    AtomicInteger sends = new AtomicInteger();
    doAnswer(
            invocation -> {
              sends.incrementAndGet();
              throw new com.appagendamento.exports.application.ExportStorageException(false);
            })
        .when(artifactStore)
        .put(anyString(), anyLong(), any(byte[].class));

    assertThat(worker.processOne()).isTrue();
    var finalState = getExport.execute(fixture.admin(), requested.job().exportId());
    assertThat(finalState.state()).isEqualTo(ExportState.FAILED_FINAL);
    assertThat(finalState.failureCode()).isEqualTo("STORAGE_REJECTED");
    assertThat(sends).hasValue(1);
    assertThat(worker.processOne()).isFalse();
  }

  @Test
  void databaseRejectsAvailableExportWithoutPersistedArtifactMetadata() {
    Fixture fixture = fixture("artifact-constraint");
    var requested =
        requestExport.execute(
            fixture.admin(),
            "artifact-constraint-" + UUID.randomUUID(),
            ExportFormat.PDF,
            "APPOINTMENTS",
            filters());

    assertThatThrownBy(
            () ->
                appJdbc.update(
                    "UPDATE agendamento.export_job SET state = 'AVAILABLE' WHERE tenant_id = ? AND export_id = ?",
                    fixture.tenantId(),
                    requested.job().exportId()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(getExport.execute(fixture.admin(), requested.job().exportId()).state())
        .isEqualTo(ExportState.REQUESTED);
  }

  @Test
  void workerRejectsSnapshotDigestMismatchWithoutPublishingAnArtifact() {
    Fixture fixture = fixture("snapshot-digest");
    var requested =
        requestExport.execute(
            fixture.admin(),
            "snapshot-digest-" + UUID.randomUUID(),
            ExportFormat.CSV,
            "APPOINTMENTS",
            filters());
    migrationJdbc.update(
        "UPDATE agendamento.export_job SET snapshot_digest = repeat('0', 64) WHERE tenant_id = ? AND export_id = ?",
        fixture.tenantId(),
        requested.job().exportId());

    assertThat(worker.processOne()).isTrue();
    var failed = getExport.execute(fixture.admin(), requested.job().exportId());
    assertThat(failed.state()).isEqualTo(ExportState.FAILED_FINAL);
    assertThat(failed.failureCode()).isEqualTo("SNAPSHOT_INVALID");
    assertThat(objects).isEmpty();
    assertThat(countAction("EXPORT_AVAILABLE", fixture.tenantId())).isZero();
  }

  @Test
  void isolatedIdentifiersAndRevokedMembershipCannotReadDownloadOrCreate() throws Exception {
    Fixture own = fixture("own");
    Fixture foreign = fixture("foreign");
    var request =
        requestExport.execute(
            own.admin(),
            "export-own-" + UUID.randomUUID(),
            ExportFormat.PDF,
            "APPOINTMENTS",
            filters());
    assertThatThrownBy(() -> getExport.execute(foreign.admin(), request.job().exportId()))
        .isInstanceOf(ExportNotFoundException.class);
    assertThat(worker.processOne()).isTrue();
    var handle = issueHandle.execute(own.admin(), request.job().exportId());
    var foreignExport =
        requestExport.execute(
            foreign.admin(),
            "export-other-" + UUID.randomUUID(),
            ExportFormat.CSV,
            "APPOINTMENTS",
            filters());
    assertThat(foreignExport.job().exportId()).isNotEqualTo(request.job().exportId());

    migrationJdbc.update(
        "UPDATE agendamento.admin_tenant_membership SET status = 'DISABLED' WHERE user_id = ?",
        own.admin().adminUserId());
    assertThatThrownBy(() -> getExport.execute(own.admin(), request.job().exportId()))
        .isInstanceOf(com.appagendamento.exports.domain.ExportAuthorizationDeniedException.class);
    assertThatThrownBy(
            () ->
                downloadExport.execute(
                    own.admin(), request.job().exportId(), handle.downloadHandle()))
        .isInstanceOf(com.appagendamento.exports.domain.ExportAuthorizationDeniedException.class);
    assertThat(countAction("EXPORT_DOWNLOADED", own.tenantId())).isZero();
  }

  @Test
  void storageReadFailureIsNormalizedAndRetryAfterRecoveryDoesNotServeOrAuditPartialContent()
      throws Exception {
    Fixture fixture = fixture("storage-read-failure");
    var requested =
        requestExport.execute(
            fixture.admin(),
            "storage-read-" + UUID.randomUUID(),
            ExportFormat.CSV,
            "APPOINTMENTS",
            filters());
    processUntilAvailable(fixture, requested.job().exportId());
    var handle = issueHandle.execute(fixture.admin(), requested.job().exportId());
    String artifactKey =
        appJdbc.queryForObject(
            "SELECT artifact_key FROM agendamento.export_job WHERE export_id = ?",
            String.class,
            requested.job().exportId());
    byte[] encrypted = objects.get(artifactKey).bytes().clone();
    when(artifactStore.get(artifactKey))
        .thenThrow(new com.appagendamento.exports.application.ExportStorageException(true))
        .thenReturn(encrypted);

    mvc.perform(
            get("/api/v1/admin/exports/{exportId}/content", requested.job().exportId())
                .with(authentication(auth(fixture.admin())))
                .header("X-Export-Download-Handle", handle.downloadHandle()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error.code").value("EXPORT_INFRASTRUCTURE_UNAVAILABLE"))
        .andExpect(header().doesNotExist("Content-Disposition"));

    assertThat(getExport.execute(fixture.admin(), requested.job().exportId()).state())
        .isEqualTo(ExportState.AVAILABLE);
    assertThat(countAction("EXPORT_DOWNLOADED", fixture.tenantId())).isZero();

    mvc.perform(
            get("/api/v1/admin/exports/{exportId}/content", requested.job().exportId())
                .with(authentication(auth(fixture.admin())))
                .header("X-Export-Download-Handle", handle.downloadHandle()))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store, private"))
        .andExpect(header().exists("Content-Disposition"))
        .andExpect(content().contentTypeCompatibleWith(new MediaType("text", "csv")));

    assertThat(countAction("EXPORT_DOWNLOADED", fixture.tenantId())).isEqualTo(1);
  }

  @Test
  void runtimeAuditDetailsCheckRejectsMissingAndJsonNullExportFieldsButPreservesValidActions() {
    Fixture fixture = fixture("audit-details-check");

    assertAuditRejected(
        fixture,
        "EXPORT_REQUESTED",
        "{\"changed_fields\":[\"export\"],\"format\":\"CSV\",\"version_before\":0,\"version_after\":1}");
    assertAuditRejected(
        fixture,
        "EXPORT_REQUESTED",
        "{\"changed_fields\":[\"export\"],\"category\":null,\"format\":\"CSV\",\"version_before\":0,\"version_after\":1}");
    assertAuditRejected(
        fixture,
        "EXPORT_AVAILABLE",
        "{\"changed_fields\":[\"export\"],\"version_after\":2,\"row_count\":1}");
    assertAuditRejected(
        fixture,
        "EXPORT_AVAILABLE",
        "{\"changed_fields\":[\"export\"],\"version_before\":1,\"version_after\":2,\"row_count\":null}");
    assertAuditRejected(fixture, "EXPORT_EXPIRED", "{\"changed_fields\":[\"export\"]}");
    assertAuditRejected(
        fixture,
        "EXPORT_EXPIRED",
        "{\"changed_fields\":[\"export\"],\"version_before\":null,\"version_after\":3}");

    insertAudit(
        fixture,
        "EXPORT_REQUESTED",
        "ADMIN",
        "TENANT_ADMIN",
        fixture.admin().adminUserId(),
        "{\"changed_fields\":[\"export\"],\"category\":\"APPOINTMENTS\",\"format\":\"CSV\",\"version_before\":0,\"version_after\":1}");
    insertAudit(
        fixture,
        "EXPORT_AVAILABLE",
        "SYSTEM",
        "EXPORT_LIFECYCLE",
        null,
        "{\"changed_fields\":[\"export\"],\"version_before\":1,\"version_after\":2,\"row_count\":4}");
    insertAudit(
        fixture,
        "EXPORT_DOWNLOADED",
        "ADMIN",
        "TENANT_ADMIN",
        fixture.admin().adminUserId(),
        "{\"changed_fields\":[\"export_download\"]}");
    insertAudit(
        fixture,
        "EXPORT_EXPIRED",
        "SYSTEM",
        "EXPORT_LIFECYCLE",
        null,
        "{\"changed_fields\":[\"export\"],\"version_before\":2,\"version_after\":3}");
    insertAudit(
        fixture,
        "TENANT_CREATED",
        "SYSTEM",
        "CONTROLLED_TENANT_PROVISIONER",
        null,
        "{\"changed_fields\":[\"name\",\"slug\",\"timezone\"]}");
  }

  @Test
  void httpRequiresCurrentAdminAndCsrfAndReturnsPrivateNoStoreState() throws Exception {
    Fixture fixture = fixture("http");
    mvc.perform(
            post("/api/v1/admin/exports")
                .with(authentication(auth(fixture.admin())))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "http-export-" + UUID.randomUUID())
                .content(requestBody()))
        .andExpect(status().isForbidden());

    String key = "http-export-" + UUID.randomUUID();
    var created =
        mvc.perform(
                post("/api/v1/admin/exports")
                    .with(authentication(auth(fixture.admin())))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", key)
                    .content(requestBody()))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.state").value("REQUESTED"))
            .andReturn();
    assertThat(created.getResponse().getContentAsString()).contains("exportId");
    var exportId =
        java.util.UUID.fromString(
            new tools.jackson.databind.ObjectMapper()
                .readTree(created.getResponse().getContentAsString())
                .get("exportId")
                .asString());
    mvc.perform(get("/api/v1/admin/exports/active").with(authentication(auth(fixture.admin()))))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("ETag", "\"1\""))
        .andExpect(jsonPath("$.exportId").value(exportId.toString()))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.failureCode").doesNotExist())
        .andExpect(jsonPath("$.snapshotCiphertext").doesNotExist())
        .andExpect(jsonPath("$.tenantId").doesNotExist())
        .andExpect(jsonPath("$.actorId").doesNotExist());
    Fixture foreign = fixture("http-active-foreign");
    mvc.perform(get("/api/v1/admin/exports/active").with(authentication(auth(foreign.admin()))))
        .andExpect(status().isNoContent())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().string(""));
    mvc.perform(
            get("/api/v1/admin/exports/{id}", exportId).with(authentication(auth(fixture.admin()))))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("ETag", "\"1\""))
        .andExpect(jsonPath("$.category").value("APPOINTMENTS"));

    assertThat(worker.processOne()).isTrue();
    mvc.perform(
            get("/api/v1/admin/exports/{id}", exportId).with(authentication(auth(fixture.admin()))))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"3\""));
    mvc.perform(get("/api/v1/admin/exports/active").with(authentication(auth(fixture.admin()))))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"3\""));
    String handleResponse =
        mvc.perform(
                post("/api/v1/admin/exports/{id}/download-token", exportId)
                    .with(authentication(auth(fixture.admin())))
                    .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String handle =
        new tools.jackson.databind.ObjectMapper()
            .readTree(handleResponse)
            .get("downloadHandle")
            .asString();
    mvc.perform(
            get("/api/v1/admin/exports/{id}/content", exportId)
                .with(authentication(auth(fixture.admin())))
                .header("X-Export-Download-Handle", handle))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store, private"))
        .andExpect(
            header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".csv")))
        .andExpect(
            header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")));

    mvc.perform(
            post("/api/v1/admin/exports")
                .with(authentication(auth(fixture.admin())))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "invalid-status-" + UUID.randomUUID())
                .content(
                    "{\"format\":\"CSV\",\"category\":\"APPOINTMENTS\","
                        + "\"filters\":{\"from\":\"2026-10-23\",\"to\":\"2026-10-23\","
                        + "\"statuses\":[\"NOT_A_STATUS\"]}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void activeReadRevalidatesMembershipAndOmitsExpiredAvailableJob() throws Exception {
    Fixture fixture = fixture("active-expiry");
    var requested =
        requestExport.execute(
            fixture.admin(),
            "active-expiry-" + UUID.randomUUID(),
            ExportFormat.CSV,
            "APPOINTMENTS",
            filters());
    assertThat(worker.processOne()).isTrue();
    Instant expiresAt = getExport.execute(fixture.admin(), requested.job().exportId()).expiresAt();
    when(clock.instant()).thenReturn(expiresAt);

    mvc.perform(get("/api/v1/admin/exports/active").with(authentication(auth(fixture.admin()))))
        .andExpect(status().isNoContent())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get("/api/v1/admin/exports/active")).andExpect(status().isUnauthorized());

    migrationJdbc.update(
        "UPDATE agendamento.admin_tenant_membership SET status = 'DISABLED' WHERE user_id = ?",
        fixture.admin().adminUserId());
    mvc.perform(get("/api/v1/admin/exports/active").with(authentication(auth(fixture.admin()))))
        .andExpect(status().isForbidden());
  }

  @Test
  void ifMatchRequiresExactStrongQuotedEtagAndTerminalRetryIsIdempotent() throws Exception {
    Fixture fixture = fixture("if-match");
    var requested =
        requestExport.execute(
            fixture.admin(),
            "if-match-" + UUID.randomUUID(),
            ExportFormat.CSV,
            "APPOINTMENTS",
            filters());
    assertThat(worker.processOne()).isTrue();
    UUID id = requested.job().exportId();
    for (String malformed :
        List.of(
            "3",
            "W/\"3\"",
            "*",
            "\"2",
            "3\"",
            "\"0\"",
            "\"03\"",
            "\"+3\"",
            " \"3\"",
            "\"1\", \"2\"",
            "\"3\", \"4\"")) {
      mvc.perform(
              delete("/api/v1/admin/exports/{id}", id)
                  .with(authentication(auth(fixture.admin())))
                  .with(csrf())
                  .header("If-Match", malformed))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("INVALID_IF_MATCH"));
    }
    mvc.perform(
            delete("/api/v1/admin/exports/{id}", id)
                .with(authentication(auth(fixture.admin())))
                .with(csrf()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_IF_MATCH"));
    mvc.perform(
            delete("/api/v1/admin/exports/{id}", id)
                .with(authentication(auth(fixture.admin())))
                .with(csrf())
                .header("If-Match", "\"1\""))
        .andExpect(status().isPreconditionFailed())
        .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    mvc.perform(
            delete("/api/v1/admin/exports/{id}", id)
                .with(authentication(auth(fixture.admin())))
                .with(csrf())
                .header("If-Match", "\"3\""))
        .andExpect(status().isNoContent());
    int auditAfterFirst = countAction("EXPORT_EXPIRED", fixture.tenantId());
    int outboxAfterFirst = countOutbox("ExportExpired", fixture.tenantId());
    mvc.perform(
            delete("/api/v1/admin/exports/{id}", id)
                .with(authentication(auth(fixture.admin())))
                .with(csrf())
                .header("If-Match", "\"1\""))
        .andExpect(status().isNoContent());
    assertThat(countAction("EXPORT_EXPIRED", fixture.tenantId()))
        .isEqualTo(auditAfterFirst)
        .isEqualTo(1);
    assertThat(countOutbox("ExportExpired", fixture.tenantId()))
        .isEqualTo(outboxAfterFirst)
        .isEqualTo(1);
  }

  @Test
  void activeRecoveryMayReturnNoContentAfterConflictingPostJobTerminates() throws Exception {
    Fixture fixture = fixture("active-race");
    mvc.perform(
            post("/api/v1/admin/exports")
                .with(authentication(auth(fixture.admin())))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "active-race-first-" + UUID.randomUUID())
                .content(requestBody()))
        .andExpect(status().isAccepted());
    mvc.perform(
            post("/api/v1/admin/exports")
                .with(authentication(auth(fixture.admin())))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "active-race-second-" + UUID.randomUUID())
                .content(requestBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("EXPORT_ALREADY_ACTIVE"));

    assertThat(worker.processOne()).isTrue();
    UUID id =
        appJdbc.queryForObject(
            "SELECT export_id FROM agendamento.export_job WHERE tenant_id = ?",
            UUID.class,
            fixture.tenantId());
    when(clock.instant()).thenReturn(getExport.execute(fixture.admin(), id).expiresAt());
    worker.expireAndClean();

    mvc.perform(get("/api/v1/admin/exports/active").with(authentication(auth(fixture.admin()))))
        .andExpect(status().isNoContent())
        .andExpect(header().string("Cache-Control", "no-store"));
    assertThat(count("export_job", fixture.tenantId())).isEqualTo(1);
    assertThat(countOutbox("ExportRequested", fixture.tenantId())).isEqualTo(1);
  }

  @Test
  void newExplicitRequestMaterializesDueExpiryBeforeClaimingTenantSlot() {
    Fixture fixture = fixture("request-after-expiry");
    var first =
        requestExport.execute(
            fixture.admin(),
            "expiry-first-" + UUID.randomUUID(),
            ExportFormat.CSV,
            "APPOINTMENTS",
            filters());
    assertThat(worker.processOne()).isTrue();
    Instant expiresAt = getExport.execute(fixture.admin(), first.job().exportId()).expiresAt();
    when(clock.instant()).thenReturn(expiresAt);

    var second =
        requestExport.execute(
            fixture.admin(),
            "expiry-second-" + UUID.randomUUID(),
            ExportFormat.PDF,
            "APPOINTMENTS",
            filters());

    assertThat(getExport.execute(fixture.admin(), first.job().exportId()).state())
        .isEqualTo(ExportState.EXPIRED);
    assertThat(second.job().state()).isEqualTo(ExportState.REQUESTED);
    assertThat(count("export_job", fixture.tenantId())).isEqualTo(2);
    assertThat(countAction("EXPORT_EXPIRED", fixture.tenantId())).isEqualTo(1);
    assertThat(countAction("EXPORT_REQUESTED", fixture.tenantId())).isEqualTo(2);
    assertThat(countOutbox("ExportExpired", fixture.tenantId())).isEqualTo(1);
    assertThat(countOutbox("ExportRequested", fixture.tenantId())).isEqualTo(2);
  }

  @Test
  void requestAuditOutboxAndReceiptRollbackTogetherAndProductionMissingInfraFailsClosed() {
    Fixture fixture = fixture("rollback");
    org.mockito.Mockito.doAnswer(
            invocation -> {
              var event = (com.appagendamento.outbox.api.OutboxEvent) invocation.getArgument(0);
              if (event.eventType().equals("ExportRequested")) {
                throw new IllegalStateException("controlled outbox failure");
              }
              return invocation.callRealMethod();
            })
        .when(outbox)
        .record(any(com.appagendamento.outbox.api.OutboxEvent.class));
    String key = "rollback-export-" + UUID.randomUUID();
    assertThatThrownBy(
            () ->
                requestExport.execute(
                    fixture.admin(), key, ExportFormat.CSV, "APPOINTMENTS", filters()))
        .hasRootCauseMessage("controlled outbox failure");
    assertThat(count("export_job", fixture.tenantId())).isZero();
    assertThat(count("idempotency_record", fixture.tenantId())).isZero();
    assertThat(countAction("EXPORT_REQUESTED", fixture.tenantId())).isZero();

    when(artifactStore.available()).thenReturn(false);
    assertThatThrownBy(
            () ->
                requestExport.execute(
                    fixture.admin(),
                    "disabled-export-" + UUID.randomUUID(),
                    ExportFormat.CSV,
                    "APPOINTMENTS",
                    filters()))
        .isInstanceOf(ExportUnavailableException.class);
    assertThat(count("export_job", fixture.tenantId())).isZero();
  }

  @Test
  void availabilityAuditAndOutboxRollbackTogetherWhenOutboxWriteFails() {
    Fixture fixture = fixture("available-rollback");
    var requested =
        requestExport.execute(
            fixture.admin(),
            "available-rollback-" + UUID.randomUUID(),
            ExportFormat.CSV,
            "APPOINTMENTS",
            filters());
    java.util.concurrent.atomic.AtomicBoolean failAvailabilityOutbox =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              var event = (com.appagendamento.outbox.api.OutboxEvent) invocation.getArgument(0);
              if (event.eventType().equals("ExportAvailable") && failAvailabilityOutbox.get()) {
                throw new IllegalStateException("controlled availability outbox failure");
              }
              return invocation.callRealMethod();
            })
        .when(outbox)
        .record(any(com.appagendamento.outbox.api.OutboxEvent.class));

    assertThatThrownBy(worker::processOne)
        .hasRootCauseMessage("controlled availability outbox failure");
    var state = getExport.execute(fixture.admin(), requested.job().exportId());
    assertThat(state.state()).isEqualTo(ExportState.PROCESSING);
    assertThat(countAction("EXPORT_AVAILABLE", fixture.tenantId())).isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.outbox_event WHERE aggregate_id = ? AND event_type = 'ExportAvailable'",
                Integer.class,
                requested.job().exportId()))
        .isZero();

    when(clock.instant()).thenReturn(NOW.plusSeconds(61));
    failAvailabilityOutbox.set(false);
    assertThat(worker.processOne()).isTrue();
    assertThat(getExport.execute(fixture.admin(), requested.job().exportId()).state())
        .isEqualTo(ExportState.AVAILABLE);
    assertThat(countAction("EXPORT_AVAILABLE", fixture.tenantId())).isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.outbox_event WHERE aggregate_id = ? AND event_type = 'ExportAvailable'",
                Integer.class,
                requested.job().exportId()))
        .isEqualTo(1);
  }

  @Test
  void runtimeGrantsDoNotAllowSnapshotMutationOrHandleDeletion() {
    assertThat(
            appJdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_app','agendamento.export_job','SELECT') "
                    + "AND has_table_privilege('agendamento_app','agendamento.export_job','INSERT') "
                    + "AND has_column_privilege('agendamento_app','agendamento.export_job','state','UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_app','agendamento.export_job','snapshot_ciphertext','UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app','agendamento.export_job','DELETE') "
                    + "AND has_table_privilege('agendamento_app','agendamento.export_download_handle','INSERT') "
                    + "AND NOT has_table_privilege('agendamento_app','agendamento.export_download_handle','DELETE')",
                Boolean.class))
        .isTrue();
  }

  private Object concurrentRequest(CyclicBarrier barrier, AuthenticatedAdmin actor, String key)
      throws Exception {
    barrier.await();
    try {
      return requestExport.execute(actor, key, ExportFormat.CSV, "APPOINTMENTS", filters());
    } catch (ExportAlreadyActiveException exception) {
      return exception;
    }
  }

  private void processUntilAvailable(Fixture fixture, UUID exportId) {
    for (int processed = 0; processed < 20; processed++) {
      var current = getExport.execute(fixture.admin(), exportId);
      if (current.state() == ExportState.AVAILABLE) return;
      if (!worker.processOne()) break;
    }
    assertThat(getExport.execute(fixture.admin(), exportId).state())
        .as("the requested export should reach AVAILABLE while draining earlier tenant jobs")
        .isEqualTo(ExportState.AVAILABLE);
  }

  private void storeInMemory(String key, long fencingVersion, byte[] body) {
    synchronized (objectLocks.computeIfAbsent(key, ignored -> new Object())) {
      Long deletedAt = tombstones.get(key);
      if (deletedAt != null && fencingVersion <= deletedAt) {
        throw new com.appagendamento.exports.application.ExportStorageException(false);
      }
      StoredArtifact previous = objects.get(key);
      if (previous != null && fencingVersion < previous.fencingVersion()) {
        throw new IllegalStateException("Stale export fencing version");
      }
      if (previous != null
          && fencingVersion == previous.fencingVersion()
          && !java.util.Arrays.equals(previous.bytes(), body)) {
        throw new IllegalStateException("Export object key received incompatible content");
      }
      assertThat(fencingVersion).isPositive();
      objects.compute(
          key,
          (ignored, current) ->
              current == null || fencingVersion > current.fencingVersion()
                  ? new StoredArtifact(fencingVersion, body.clone())
                  : current);
    }
  }

  private Fixture fixture(String suffix) {
    UUID tenant = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID serviceId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    String prefix = "export-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id,name,slug,timezone,status) VALUES (?,?,?,'America/Sao_Paulo','ACTIVE')",
        tenant,
        "Export tenant",
        prefix);
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_user (user_id,identity_issuer,identity_subject) VALUES (?,?,?)",
        adminId,
        ISSUER,
        subject);
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_tenant_membership (user_id,tenant_id,role) VALUES (?,?,'TENANT_ADMIN')",
        adminId,
        tenant);
    migrationJdbc.update(
        "INSERT INTO agendamento.client (client_id,tenant_id,display_name,normalized_phone) VALUES (?,?,?,?)",
        clientId,
        tenant,
        "Private Client Name",
        "+5511999991234");
    migrationJdbc.update(
        "INSERT INTO agendamento.service (service_id,tenant_id,name,normalized_name,duration_minutes,buffer_after_minutes,status,version) VALUES (?,?,?,? ,30,15,'ACTIVE',1)",
        serviceId,
        tenant,
        "=SUM(1,1)",
        "export-" + suffix);
    Instant startsAt = Instant.parse("2026-10-23T12:00:00Z");
    migrationJdbc.update(
        "INSERT INTO agendamento.appointment (appointment_id,tenant_id,client_id,service_id,starts_at,service_ends_at,occupied_until,time_zone,client_name_snapshot,service_name_snapshot,service_duration_minutes,service_buffer_after_minutes,status,created_at,version) VALUES (?,?,?,?,?,?,?,'America/Sao_Paulo','Private Client Name','=SUM(1,1)',30,15,'CONFIRMED',?,1)",
        appointmentId,
        tenant,
        clientId,
        serviceId,
        java.sql.Timestamp.from(startsAt),
        java.sql.Timestamp.from(startsAt.plusSeconds(1800)),
        java.sql.Timestamp.from(startsAt.plusSeconds(2700)),
        java.sql.Timestamp.from(startsAt.minusSeconds(86400)));
    return new Fixture(tenant, serviceId, appointmentId, new TestAdmin(adminId, subject, tenant));
  }

  private int count(String table, UUID tenant) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento." + table + " WHERE tenant_id = ?",
        Integer.class,
        tenant);
  }

  private int countAction(String action, UUID tenant) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.audit_event WHERE tenant_id = ? AND action = ?",
        Integer.class,
        tenant,
        action);
  }

  private void assertAuditRejected(Fixture fixture, String action, String details) {
    assertThatThrownBy(
            () ->
                insertAudit(
                    fixture,
                    action,
                    action.equals("EXPORT_REQUESTED") ? "ADMIN" : "SYSTEM",
                    action.equals("EXPORT_REQUESTED") ? "TENANT_ADMIN" : "EXPORT_LIFECYCLE",
                    action.equals("EXPORT_REQUESTED") ? fixture.admin().adminUserId() : null,
                    details))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  private void insertAudit(
      Fixture fixture,
      String action,
      String actorType,
      String actorRef,
      UUID actorId,
      String details) {
    appJdbc.update(
        "INSERT INTO agendamento.audit_event (event_id, tenant_id, actor_id, actor_type, actor_ref, action, target_type, target_id, result, correlation_id, trace_id, details) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'COMMITTED', ?, ?, ?::jsonb)",
        UUID.randomUUID(),
        fixture.tenantId(),
        actorId,
        actorType,
        actorRef,
        action,
        action.equals("TENANT_CREATED") ? "TENANT" : "EXPORT",
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        details);
  }

  private int countOutbox(String eventType, UUID tenant) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.outbox_event WHERE tenant_id = ? AND event_type = ?",
        Integer.class,
        tenant,
        eventType);
  }

  private static ExportFilters filters() {
    return new ExportFilters(
        LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 23), java.util.Set.of(), null);
  }

  private static String requestBody() {
    return "{\"format\":\"CSV\",\"category\":\"APPOINTMENTS\",\"filters\":{\"from\":\"2026-10-23\",\"to\":\"2026-10-23\"}}";
  }

  private static UsernamePasswordAuthenticationToken auth(AuthenticatedAdmin admin) {
    return new UsernamePasswordAuthenticationToken(
        admin, "n/a", List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN")));
  }

  private record Fixture(UUID tenantId, UUID serviceId, UUID appointmentId, TestAdmin admin) {}

  private record StoredArtifact(long fencingVersion, byte[] bytes) {}

  private record TestAdmin(UUID adminUserId, String subject, UUID tenantId)
      implements AuthenticatedAdmin,
          org.springframework.security.core.AuthenticatedPrincipal,
          java.io.Serializable {

    @Override
    public String getName() {
      return subject;
    }

    @Override
    public String issuer() {
      return ISSUER;
    }

    @Override
    public String role() {
      return "TENANT_ADMIN";
    }
  }
}
