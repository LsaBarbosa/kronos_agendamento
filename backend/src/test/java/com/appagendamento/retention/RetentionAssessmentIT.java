package com.appagendamento.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.support.PostgresTestEnvironment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
class RetentionAssessmentIT {

  private static final String ISSUER = "https://identity.test/realms/agendamento";
  private static final String PATH = "/api/v1/admin/retention/assessment";

  @Autowired private JdbcTemplate appJdbc;
  @Autowired private MockMvc mvc;
  private JdbcTemplate migrationJdbc;
  private UUID tenantId;
  private UUID adminId;
  private String subject;
  private AuthenticatedAdmin actor;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void createTenantAndAdmin() {
    migrationJdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
    tenantId = UUID.randomUUID();
    adminId = UUID.randomUUID();
    subject = UUID.randomUUID().toString();
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id,name,slug,timezone,status) VALUES (?,?,?,'UTC','ACTIVE')",
        tenantId,
        "Retention assessment test",
        "retention-" + tenantId.toString().substring(0, 8));
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_user (user_id,identity_issuer,identity_subject) VALUES (?,?,?)",
        adminId,
        ISSUER,
        subject);
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_tenant_membership (user_id,tenant_id,role) VALUES (?,?,'TENANT_ADMIN')",
        adminId,
        tenantId);
    actor = new TestAdmin(adminId, subject, tenantId);
  }

  @Test
  void returnsExplicitZeroAndUnassessedStatesWhenTenantHasNoRecords() throws Exception {
    MvcResult result =
        mvc.perform(get(PATH).with(authentication(token(actor))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.informationalOnly").value(true))
            .andExpect(jsonPath("$.readConsistency").value("NON_ATOMIC_READ_COMMITTED"))
            .andExpect(jsonPath("$.countLimit").value(10000))
            .andExpect(jsonPath("$.sourceQueryTimeoutSeconds").value(2))
            .andExpect(jsonPath("$.tenantLifecycleStatus").value("ACTIVE"))
            .andExpect(jsonPath("$.policy.initialPostCancellationOrSuspensionMonths").value(12))
            .andExpect(jsonPath("$.policy.startMarker").value("UNRESOLVED"))
            .andExpect(jsonPath("$.policy.categoryPolicies").value("NOT_CONFIGURED"))
            .andExpect(jsonPath("$.policy.holdAuthority").value("UNDEFINED"))
            .andExpect(
                jsonPath("$.categories[?(@.code=='CLIENT_RECORDS')].evaluationStatus")
                    .value("ASSESSED"))
            .andExpect(
                jsonPath("$.categories[?(@.code=='CLIENT_RECORDS')].counts.records.value").value(0))
            .andExpect(
                jsonPath("$.categories[?(@.code=='CLIENT_RECORDS')].counts.records.status")
                    .value("EXACT"))
            .andExpect(
                jsonPath("$.categories[?(@.code=='APPOINTMENTS')].counts.futureConfirmed.value")
                    .value(0))
            .andExpect(
                jsonPath("$.categories[?(@.code=='BILLING_OPERATIONS')].facts.subscriptionStatus")
                    .value("ABSENT"))
            .andExpect(
                jsonPath("$.categories[?(@.code=='AUDIT_EVENTS')].evaluationStatus")
                    .value("NOT_EVALUATED"))
            .andExpect(
                jsonPath("$.categories[?(@.code=='AUDIT_EVENTS')].sourceStatus")
                    .value("NOT_QUERIED"))
            .andExpect(
                jsonPath("$.categories[?(@.code=='AUDIT_EVENTS')].counts.records").doesNotExist())
            .andReturn();

    assertThat(result.getResponse().getContentAsString())
        .contains("ASSESSMENT_ONLY_NO_DISCARD_AUTHORIZATION");
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("RELEASED", "DELETE_ALLOWED");
  }

  @Test
  void reportsPersistedFutureBookingsAndPendingEffectsWithoutPrivateContentOrWrites()
      throws Exception {
    UUID clientId = UUID.randomUUID();
    UUID serviceId = UUID.randomUUID();
    UUID futureAppointment = UUID.randomUUID();
    Instant now = Instant.now();
    Instant startsAt = now.plusSeconds(86_400);
    Instant endsAt = startsAt.plusSeconds(1_800);
    migrationJdbc.update(
        "INSERT INTO agendamento.client (client_id,tenant_id,display_name,normalized_phone) VALUES (?,?,?,?)",
        clientId,
        tenantId,
        "PRIVATE_RETENTION_NAME",
        "+14155550123");
    migrationJdbc.update(
        "INSERT INTO agendamento.service (service_id,tenant_id,name,normalized_name,duration_minutes,buffer_after_minutes,status) VALUES (?,?,?,'retention test',30,0,'ACTIVE')",
        serviceId,
        tenantId,
        "PRIVATE_SERVICE_NAME");
    migrationJdbc.update(
        """
        INSERT INTO agendamento.appointment
          (appointment_id,tenant_id,client_id,service_id,starts_at,service_ends_at,occupied_until,
           time_zone,client_name_snapshot,service_name_snapshot,service_duration_minutes,
           service_buffer_after_minutes,status,created_at,version)
        VALUES (?,?,?,?,?,?,?,'UTC','PRIVATE_RETENTION_NAME','PRIVATE_SERVICE_NAME',30,0,
                'CONFIRMED',clock_timestamp(),1)
        """,
        futureAppointment,
        tenantId,
        clientId,
        serviceId,
        java.sql.Timestamp.from(startsAt),
        java.sql.Timestamp.from(endsAt),
        java.sql.Timestamp.from(endsAt));

    UUID pendingEventId = insertOutbox("PENDING", "TENANT_RENAMED");
    UUID unknownEventId = insertOutbox("RECONCILIATION_REQUIRED", "TENANT_RENAMED");
    insertNotification(
        UUID.randomUUID(),
        futureAppointment,
        clientId,
        pendingEventId,
        "APPOINTMENT_CONFIRMED",
        "PENDING");
    insertNotification(
        UUID.randomUUID(),
        futureAppointment,
        clientId,
        unknownEventId,
        "APPOINTMENT_CANCELLED",
        "OUTCOME_UNKNOWN");
    insertUnknownCheckout();

    UUID otherTenant = createOtherTenant();
    UUID otherClientId = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.client (client_id,tenant_id,display_name,normalized_phone) VALUES (?,?,?,?)",
        otherClientId,
        otherTenant,
        "OTHER_TENANT_PRIVATE_NAME",
        "+14155550124");

    var before = businessCounts();
    MvcResult result =
        mvc.perform(get(PATH).with(authentication(token(actor))))
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.categories[?(@.code=='CLIENT_RECORDS')].counts.records.value").value(1))
            .andExpect(
                jsonPath("$.categories[?(@.code=='APPOINTMENTS')].counts.records.value").value(1))
            .andExpect(
                jsonPath("$.categories[?(@.code=='APPOINTMENTS')].counts.futureConfirmed.value")
                    .value(1))
            .andExpect(
                jsonPath(
                        "$.categories[?(@.code=='NOTIFICATION_INTENTS')].counts.pendingOrClaimed.value")
                    .value(1))
            .andExpect(
                jsonPath(
                        "$.categories[?(@.code=='NOTIFICATION_INTENTS')].counts.outcomeUnknown.value")
                    .value(1))
            .andExpect(
                jsonPath("$.categories[?(@.code=='OUTBOX_EVENTS')].counts.pendingOrClaimed.value")
                    .value(1))
            .andExpect(
                jsonPath(
                        "$.categories[?(@.code=='OUTBOX_EVENTS')].counts.reconciliationRequired.value")
                    .value(1))
            .andExpect(
                jsonPath(
                        "$.categories[?(@.code=='BILLING_OPERATIONS')].counts.checkoutOutcomeUnknown.value")
                    .value(1))
            .andExpect(
                jsonPath("$.blockers")
                    .value(
                        org.hamcrest.Matchers.hasItems(
                            "FUTURE_APPOINTMENTS_PRESENT",
                            "NOTIFICATION_EFFECTS_PENDING",
                            "NOTIFICATION_OUTCOME_UNKNOWN",
                            "OUTBOX_EFFECTS_PENDING",
                            "OUTBOX_RECONCILIATION_REQUIRED",
                            "BILLING_OUTCOME_UNKNOWN")))
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(body)
        .doesNotContain(
            "PRIVATE_RETENTION_NAME",
            "+14155550123",
            "PRIVATE_SERVICE_NAME",
            "OTHER_TENANT_PRIVATE_NAME",
            "789456123",
            "private-account",
            "terms-private",
            "offer-private",
            futureAppointment.toString(),
            clientId.toString());
    assertThat(businessCounts()).isEqualTo(before);
  }

  @Test
  void currentAssociationIsRequiredAndSecondTenantCannotSeeFirstTenantFacts() throws Exception {
    UUID otherTenant = createOtherTenant();
    UUID clientId = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.client (client_id,tenant_id,display_name,normalized_phone) VALUES (?,?,?,?)",
        clientId,
        tenantId,
        "PRIVATE_NAME",
        "+14155550125");
    UUID otherAdminId = UUID.randomUUID();
    String otherSubject = UUID.randomUUID().toString();
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_user (user_id,identity_issuer,identity_subject) VALUES (?,?,?)",
        otherAdminId,
        ISSUER,
        otherSubject);
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_tenant_membership (user_id,tenant_id,role) VALUES (?,?,'TENANT_ADMIN')",
        otherAdminId,
        otherTenant);
    AuthenticatedAdmin otherActor = new TestAdmin(otherAdminId, otherSubject, otherTenant);
    String otherBody =
        mvc.perform(get(PATH).with(authentication(token(otherActor))))
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.categories[?(@.code=='CLIENT_RECORDS')].counts.records.value").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(otherBody).doesNotContain("PRIVATE_NAME", clientId.toString());

    migrationJdbc.update(
        "UPDATE agendamento.admin_tenant_membership SET status='DISABLED' WHERE user_id=?",
        adminId);
    mvc.perform(get(PATH).with(authentication(token(actor)))).andExpect(status().isForbidden());
  }

  private UUID insertOutbox(String state, String eventType) {
    UUID eventId = UUID.randomUUID();
    migrationJdbc.update(
        """
        INSERT INTO agendamento.outbox_event
          (event_id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,
           schema_version,operation_id,correlation_id,payload,state)
        VALUES (?,?,'TENANT',?,1,?,1,?,?,?::jsonb,?)
        """,
        eventId,
        tenantId,
        tenantId,
        eventType,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "{\"tenantId\":\"" + tenantId + "\",\"version\":1}",
        state);
    return eventId;
  }

  private void insertNotification(
      UUID notificationId,
      UUID appointmentId,
      UUID clientId,
      UUID sourceEventId,
      String messageType,
      String state) {
    migrationJdbc.update(
        """
        INSERT INTO agendamento.notification_intent
          (notification_id,tenant_id,appointment_id,appointment_version,message_type,
           recipient_client_id,source_event_id,state,attempt_count,next_attempt_at,
           external_idempotency_key,last_evidence_sequence,created_at,due_at)
        VALUES (?,?,?,1,?,?,?, ?,0,clock_timestamp(),?,-1,
                clock_timestamp(),clock_timestamp())
        """,
        notificationId,
        tenantId,
        appointmentId,
        messageType,
        clientId,
        sourceEventId,
        state,
        "retention-test-" + notificationId);
  }

  private void insertUnknownCheckout() {
    migrationJdbc.update(
        """
        INSERT INTO agendamento.billing_checkout_operation
          (operation_id,tenant_id,plan_code,amount_minor_units,currency,terms_version,
           offer_version,expected_subscription_version,provider_code,provider_account_id,
           state,last_provider_sequence,created_at,updated_at)
        VALUES (?,?, 'MONTHLY',789456123,'USD','terms-private','offer-private',0,
                'test.provider','private-account','OUTCOME_UNKNOWN',0,clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        tenantId);
  }

  private UUID createOtherTenant() {
    UUID otherTenant = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id,name,slug,timezone,status) VALUES (?,? ,?,'UTC','ACTIVE')",
        otherTenant,
        "Other tenant",
        "other-retention-" + otherTenant.toString().substring(0, 8));
    return otherTenant;
  }

  private List<Integer> businessCounts() {
    return List.of(
        tableCount("client"),
        tableCount("appointment"),
        tableCount("notification_intent"),
        tableCount("outbox_event"),
        tableCount("billing_checkout_operation"),
        tableCount("audit_event"));
  }

  private int tableCount(String table) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento." + table + " WHERE tenant_id = ?",
        Integer.class,
        tenantId);
  }

  private static UsernamePasswordAuthenticationToken token(AuthenticatedAdmin admin) {
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
