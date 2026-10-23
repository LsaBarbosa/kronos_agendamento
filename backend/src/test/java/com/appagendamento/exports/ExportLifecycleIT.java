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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                "SELECT count(*) FROM agendamento.export_job WHERE tenant_id = ? AND state IN ('REQUESTED','PROCESSING','RETRY_WAIT')",
                Integer.class,
                fixture.tenantId()))
        .isEqualTo(1);
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
    mvc.perform(
            get("/api/v1/admin/exports/{id}", exportId).with(authentication(auth(fixture.admin()))))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.category").value("APPOINTMENTS"));

    assertThat(worker.processOne()).isTrue();
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
