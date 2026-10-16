package com.appagendamento.publicpage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.appagendamento.availability.application.AppointmentOccupancyQuery;
import com.appagendamento.availability.application.CalculateAvailabilityForDateRange;
import com.appagendamento.availability.application.ReplaceAvailabilityPolicy;
import com.appagendamento.availability.application.ReplaceWeeklySchedule;
import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.catalog.application.CreateService;
import com.appagendamento.catalog.application.SetServiceStatus;
import com.appagendamento.catalog.domain.ServiceStatus;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.publicpage.application.ChangePublicationStatus;
import com.appagendamento.publicpage.application.GetPublicBusiness;
import com.appagendamento.publicpage.application.PublicationEvidence;
import com.appagendamento.publicpage.application.UpdatePublicContact;
import com.appagendamento.support.PostgresTestEnvironment;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
      "identity.oidc.redirect-uri={baseUrl}/login/oauth2/code/keycloak"
    })
@AutoConfigureMockMvc
class PublicPageIT {

  private static final String ISSUER = "https://identity.test/realms/agendamento";
  private static final ZoneId TENANT_ZONE = ZoneId.of("America/Sao_Paulo");

  @Autowired private JdbcTemplate appJdbc;
  @Autowired private CreateService createService;
  @Autowired private SetServiceStatus setServiceStatus;
  @Autowired private ReplaceWeeklySchedule replaceWeeklySchedule;
  @Autowired private ReplaceAvailabilityPolicy replaceAvailabilityPolicy;
  @Autowired private UpdatePublicContact updatePublicContact;
  @Autowired private ChangePublicationStatus changePublicationStatus;
  @Autowired private CalculateAvailabilityForDateRange calculateAvailability;
  @Autowired private GetPublicBusiness getPublicBusiness;
  @Autowired private MockMvc mvc;
  @MockitoBean private PublicationEvidence publicationEvidence;
  @MockitoBean private AppointmentOccupancyQuery appointmentOccupancyQuery;

  private JdbcTemplate migrationJdbc;
  private UUID tenantId;
  private UUID serviceId;
  private AuthenticatedAdmin actor;
  private String slug;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void preparePublicTenant() {
    when(publicationEvidence.unavailableEvidence(any())).thenReturn(List.of());
    when(appointmentOccupancyQuery.findOverlapping(any(), any(), any())).thenReturn(List.of());
    migrationJdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
    tenantId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    slug = "public-" + UUID.randomUUID().toString().substring(0, 8);
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Public test business', ?, ?, 'ACTIVE')",
        tenantId,
        slug,
        TENANT_ZONE.getId());
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_user (user_id, identity_issuer, identity_subject) VALUES (?, ?, ?)",
        adminId,
        ISSUER,
        subject);
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_tenant_membership (user_id, tenant_id, role) VALUES (?, ?, 'TENANT_ADMIN')",
        adminId,
        tenantId);
    actor = new TestAdmin(adminId, subject, tenantId);

    serviceId =
        createService
            .execute(
                actor,
                "public-service-" + UUID.randomUUID(),
                "Consulta",
                "Atendimento de demonstração",
                45,
                0,
                ServiceStatus.ACTIVE)
            .value()
            .serviceId();
    replaceWeeklySchedule.execute(
        actor,
        "public-schedule-" + UUID.randomUUID(),
        0,
        List.of(new WeeklyPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.NOON)));
    replaceAvailabilityPolicy.execute(
        actor, "public-policy-" + UUID.randomUUID(), 0, 30, 12, 30, 4, 6);
    updatePublicContact.execute(actor, "public-contact-" + UUID.randomUUID(), "+5511999990000", 0);
  }

  @Test
  void activeTenantStartsUnpublishedAndExplicitPublishExposesOnlyPublicFields() throws Exception {
    mvc.perform(get("/api/v1/public/" + slug)).andExpect(status().isNotFound());
    assertThat(
            appJdbc.queryForObject(
                "SELECT published FROM agendamento.tenant_publication WHERE tenant_id = ?",
                Boolean.class,
                tenantId))
        .isFalse();

    String publishKey = "publish-" + UUID.randomUUID();
    mvc.perform(
            put("/api/v1/admin/publication")
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", publishKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"published\":true,\"expectedVersion\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.published").value(true))
        .andExpect(jsonPath("$.ready").value(true));

    mvc.perform(get("/api/v1/public/" + slug))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Public test business"))
        .andExpect(jsonPath("$.timeZone").value(TENANT_ZONE.getId()))
        .andExpect(jsonPath("$.tenantId").doesNotExist())
        .andExpect(jsonPath("$.published").doesNotExist())
        .andExpect(jsonPath("$.version").doesNotExist());
    mvc.perform(
            put("/api/v1/admin/publication")
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", publishKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"published\":true,\"expectedVersion\":1}"))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(2));
    mvc.perform(
            put("/api/v1/admin/publication")
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "stale-publication-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"published\":false,\"expectedVersion\":1}"))
        .andExpect(status().isConflict());
    assertThat(countAudit("PUBLICATION_PUBLISHED")).isEqualTo(1);
    mvc.perform(get("/api/v1/public/" + slug + "/services"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].serviceId").value(serviceId.toString()))
        .andExpect(jsonPath("$[0].durationMinutes").value(45))
        .andExpect(jsonPath("$[0].bufferAfterMinutes").doesNotExist())
        .andExpect(jsonPath("$[0].tenantId").doesNotExist());

    LocalDate nextMonday =
        LocalDate.now(TENANT_ZONE).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    mvc.perform(
            get("/api/v1/public/" + slug + "/availability")
                .param("serviceId", serviceId.toString())
                .param("from", nextMonday.toString())
                .param("to", nextMonday.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timeZone").value(TENANT_ZONE.getId()))
        .andExpect(jsonPath("$.days[0].date").value(nextMonday.toString()))
        .andExpect(jsonPath("$.days[0].slots[0].startsAt").exists());
  }

  @Test
  void publicAvailabilityKeepsPublicationAndCalculationInOneReadSnapshot() throws Exception {
    publish();
    LocalDate nextMonday =
        LocalDate.now(TENANT_ZONE).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    CountDownLatch gateChecked = new CountDownLatch(1);
    CountDownLatch unpublishCommitted = new CountDownLatch(1);
    AtomicBoolean publicationVisibleAtOccupancyRead = new AtomicBoolean();
    when(appointmentOccupancyQuery.findOverlapping(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              publicationVisibleAtOccupancyRead.set(
                  appJdbc.queryForObject(
                      "SELECT published FROM agendamento.tenant_publication WHERE tenant_id = ?",
                      Boolean.class,
                      tenantId));
              return List.of();
            });

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> read =
          executor.submit(
              () ->
                  calculateAvailability.executeForAuthorizedTenant(
                      () -> {
                        var business = getPublicBusiness.execute(slug);
                        gateChecked.countDown();
                        try {
                          if (!unpublishCommitted.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Despublicação não foi confirmada");
                          }
                        } catch (InterruptedException exception) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(exception);
                        }
                        return business.tenantId();
                      },
                      serviceId,
                      nextMonday,
                      nextMonday));

      assertThat(gateChecked.await(10, TimeUnit.SECONDS)).isTrue();
      changePublicationStatus.execute(
          actor, "unpublish-during-read-" + UUID.randomUUID(), false, 2);
      unpublishCommitted.countDown();
      read.get(10, TimeUnit.SECONDS);
    } finally {
      unpublishCommitted.countDown();
      executor.shutdownNow();
    }

    assertThat(publicationVisibleAtOccupancyRead).isTrue();
    assertThat(
            appJdbc.queryForObject(
                "SELECT published FROM agendamento.tenant_publication WHERE tenant_id = ?",
                Boolean.class,
                tenantId))
        .isFalse();
  }

  @Test
  void publicationMutationRequiresAuthenticationAndCsrfAndTelemetryRejectsExtraFields()
      throws Exception {
    publish();
    String body = "{\"published\":true,\"expectedVersion\":1}";
    mvc.perform(
            put("/api/v1/admin/publication")
                .header("Idempotency-Key", "anonymous-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());

    mvc.perform(
            put("/api/v1/admin/publication")
                .with(authentication(principalToken(actor)))
                .header("Idempotency-Key", "csrf-missing-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());

    mvc.perform(
            post("/api/v1/public/" + slug + "/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"SERVICE_SELECTED\"}"))
        .andExpect(status().isNoContent());

    mvc.perform(
            post("/api/v1/public/" + slug + "/telemetry")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"SERVICE_SELECTED\",\"phone\":\"must-not-be-accepted\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void concurrentPublicationCommandsWithSameVersionHaveOneWinner() throws Exception {
    CyclicBarrier readinessBarrier = new CyclicBarrier(2);
    when(publicationEvidence.unavailableEvidence(any()))
        .thenAnswer(
            invocation -> {
              readinessBarrier.await(10, TimeUnit.SECONDS);
              return List.of();
            });

    try (ExecutorService publishers = Executors.newFixedThreadPool(2)) {
      Future<Boolean> first =
          publishers.submit(() -> publishConcurrently("publish-race-a-" + UUID.randomUUID()));
      Future<Boolean> second =
          publishers.submit(() -> publishConcurrently("publish-race-b-" + UUID.randomUUID()));
      assertThat(first.get()).isNotEqualTo(second.get());
    }

    assertThat(
            appJdbc.queryForObject(
                "SELECT published FROM agendamento.tenant_publication WHERE tenant_id = ?",
                Boolean.class,
                tenantId))
        .isTrue();
    assertThat(
            appJdbc.queryForObject(
                "SELECT version FROM agendamento.tenant_publication WHERE tenant_id = ?",
                Long.class,
                tenantId))
        .isEqualTo(2);
    assertThat(countAudit("PUBLICATION_PUBLISHED")).isEqualTo(1);
  }

  private boolean publishConcurrently(String idempotencyKey) {
    try {
      changePublicationStatus.execute(actor, idempotencyKey, true, 1);
      return true;
    } catch (
        com.appagendamento.publicpage.application.PublicationVersionConflictException conflict) {
      return false;
    }
  }

  @Test
  void serviceFromAnotherTenantIsNotRevealedAndUnpublishBlocksDirectReads() throws Exception {
    UUID otherTenantId = UUID.randomUUID();
    String otherSlug = "other-" + UUID.randomUUID().toString().substring(0, 8);
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Other business', ?, 'UTC', 'ACTIVE')",
        otherTenantId,
        otherSlug);
    UUID otherServiceId = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.service (service_id, tenant_id, name, normalized_name, description, duration_minutes, buffer_after_minutes, status, version) VALUES (?, ?, 'Private service', 'private service', 'Private description', 30, 0, 'ACTIVE', 1)",
        otherServiceId,
        otherTenantId);
    publish();

    mvc.perform(get("/api/v1/public/" + slug + "/services/" + otherServiceId))
        .andExpect(status().isNotFound());
    mvc.perform(
            put("/api/v1/admin/publication")
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "unpublish-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"published\":false,\"expectedVersion\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.published").value(false));
    mvc.perform(get("/api/v1/public/" + slug)).andExpect(status().isNotFound());
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.service WHERE tenant_id = ? AND service_id = ?",
                Integer.class,
                tenantId,
                serviceId))
        .isEqualTo(1);
  }

  @Test
  void losingARequiredActiveServiceClosesEveryPublicReadWithoutDeletingData() throws Exception {
    publish();
    mvc.perform(get("/api/v1/public/" + slug)).andExpect(status().isOk());

    Long serviceVersion =
        appJdbc.queryForObject(
            "SELECT version FROM agendamento.service WHERE tenant_id = ? AND service_id = ?",
            Long.class,
            tenantId,
            serviceId);
    setServiceStatus.execute(
        actor,
        serviceId,
        "deactivate-public-service-" + UUID.randomUUID(),
        serviceVersion,
        ServiceStatus.INACTIVE);
    mvc.perform(get("/api/v1/public/" + slug)).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/public/" + slug + "/services")).andExpect(status().isNotFound());
    assertThat(
            appJdbc.queryForObject(
                "SELECT published FROM agendamento.tenant_publication WHERE tenant_id = ?",
                Boolean.class,
                tenantId))
        .isTrue();
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.service WHERE tenant_id = ? AND service_id = ?",
                Integer.class,
                tenantId,
                serviceId))
        .isEqualTo(1);
  }

  @Test
  void auditFailureRollsBackPublicationAndIdempotencyResult() throws Exception {
    migrationJdbc.execute(
        "CREATE FUNCTION agendamento.reject_publication_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action = 'PUBLICATION_PUBLISHED' THEN RAISE EXCEPTION 'injected publication audit failure' USING ERRCODE = '23514'; END IF; RETURN NEW; END $$");
    migrationJdbc.execute(
        "CREATE TRIGGER reject_publication_audit BEFORE INSERT ON agendamento.audit_event FOR EACH ROW EXECUTE FUNCTION agendamento.reject_publication_audit()");
    String key = "publication-audit-failure-" + UUID.randomUUID();
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  mvc.perform(
                      put("/api/v1/admin/publication")
                          .with(authentication(principalToken(actor)))
                          .with(csrf())
                          .header("Idempotency-Key", key)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{\"published\":true,\"expectedVersion\":1}")))
          .isInstanceOf(Exception.class);
    } finally {
      migrationJdbc.execute("DROP TRIGGER reject_publication_audit ON agendamento.audit_event");
      migrationJdbc.execute("DROP FUNCTION agendamento.reject_publication_audit()");
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT published FROM agendamento.tenant_publication WHERE tenant_id = ?",
                Boolean.class,
                tenantId))
        .isFalse();
    assertThat(
            appJdbc.queryForObject(
                "SELECT version FROM agendamento.tenant_publication WHERE tenant_id = ?",
                Long.class,
                tenantId))
        .isEqualTo(1);
    assertThat(countAudit("PUBLICATION_PUBLISHED")).isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'tenant.publication.status.v1'",
                Integer.class,
                tenantId))
        .isZero();
  }

  private void publish() throws Exception {
    mvc.perform(
            put("/api/v1/admin/publication")
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "publish-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"published\":true,\"expectedVersion\":1}"))
        .andExpect(status().isOk());
  }

  private int countAudit(String action) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.audit_event WHERE tenant_id = ? AND action = ?",
        Integer.class,
        tenantId,
        action);
  }

  private static UsernamePasswordAuthenticationToken principalToken(AuthenticatedAdmin admin) {
    return UsernamePasswordAuthenticationToken.authenticated(
        admin, "N/A", List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN")));
  }

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
