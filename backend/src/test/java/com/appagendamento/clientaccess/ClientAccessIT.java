package com.appagendamento.clientaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.appagendamento.clientaccess.api.AccessChallengeAccepted;
import com.appagendamento.clientaccess.api.AccessCodeSender;
import com.appagendamento.clientaccess.application.AccessChallengeInvalidException;
import com.appagendamento.clientaccess.application.ClientAccessService;
import com.appagendamento.support.PostgresTestEnvironment;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ClientAccessIT {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate appJdbc;
  @Autowired private ClientAccessService service;
  @MockitoBean private AccessCodeSender sender;
  @Autowired private ObjectMapper mapper;
  @MockitoBean private Clock clock;

  private JdbcTemplate ownerJdbc;
  private UUID tenantId;
  private String slug;
  private static final String PHONE = "+5511999991122";
  private volatile Instant now;
  private final Map<UUID, String> sentCodes = new ConcurrentHashMap<>();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void prepareTenantAndClient() {
    org.mockito.Mockito.doAnswer(
            invocation -> {
              UUID challengeId = invocation.getArgument(1);
              String code = invocation.getArgument(3);
              sentCodes.putIfAbsent(challengeId, code);
              return AccessCodeSender.DeliveryResult.ACCEPTED;
            })
        .when(sender)
        .send(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    sentCodes.clear();
    now = Instant.parse("2026-10-17T12:00:00Z");
    org.mockito.Mockito.when(clock.instant()).thenAnswer(invocation -> now);
    ownerJdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
    tenantId = UUID.randomUUID();
    slug = "client-access-" + UUID.randomUUID().toString().substring(0, 8);
    ownerJdbc.update(
        "INSERT INTO agendamento.tenant(tenant_id, name, slug, timezone, status) VALUES (?, 'Access fixture', ?, 'America/Sao_Paulo', 'ACTIVE')",
        tenantId,
        slug);
    ownerJdbc.update(
        "INSERT INTO agendamento.client(client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Cliente Teste', ?)",
        UUID.randomUUID(),
        tenantId,
        PHONE);
  }

  @Test
  void requestIsNeutralAndVerificationCreatesOnlyRestrictedCookieSession() throws Exception {
    assertThat(
            ownerJdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_app', 'agendamento.client_access_request_intent', 'SELECT') "
                    + "AND has_table_privilege('agendamento_app', 'agendamento.client_access_request_intent', 'INSERT') "
                    + "AND has_table_privilege('agendamento_app', 'agendamento.client_access_send_intent', 'SELECT') "
                    + "AND has_table_privilege('agendamento_app', 'agendamento.client_access_send_intent', 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_app', 'agendamento.client_access_request_intent', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', 'agendamento.client_access_request_intent', 'DELETE')",
                Boolean.class))
        .isTrue();
    assertThat(
            ownerJdbc.queryForObject(
                "SELECT has_column_privilege('agendamento_app', 'agendamento.client_access_challenge', 'status', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_app', 'agendamento.client_access_challenge', 'code_verifier', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_app', 'agendamento.client_access_challenge', 'phone_ciphertext', 'UPDATE')",
                Boolean.class))
        .isTrue();
    MvcResult known = request(PHONE, "access-request-" + UUID.randomUUID());
    MvcResult unknown = request("+5511999991123", "access-request-" + UUID.randomUUID());
    assertThat(known.getResponse().getStatus()).isEqualTo(202);
    assertThat(unknown.getResponse().getStatus()).isEqualTo(202);
    assertThat(known.getResponse().getContentAsString())
        .doesNotContain(PHONE, "code", "Cliente Teste");
    assertThat(unknown.getResponse().getContentAsString()).doesNotContain("+5511999991123", "code");
    AccessChallengeAccepted challenge =
        mapper.readValue(known.getResponse().getContentAsString(), AccessChallengeAccepted.class);
    AccessChallengeAccepted unknownChallenge =
        mapper.readValue(unknown.getResponse().getContentAsString(), AccessChallengeAccepted.class);
    assertThat(unknownChallenge.challengeAccepted()).isEqualTo(challenge.challengeAccepted());
    assertThat(unknownChallenge.expiresInSeconds()).isEqualTo(challenge.expiresInSeconds());
    assertThat(sentCodes.get(unknownChallenge.challengeId())).matches("[0-9]{6}");
    assertThat(
            ownerJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.client_access_challenge WHERE fingerprint_key_id <> 'test-fingerprint-v1'",
                Long.class))
        .isZero();
    assertThatThrownBy(
            () ->
                appJdbc.update(
                    "UPDATE agendamento.client_access_challenge SET code_verifier = ? WHERE challenge_id = ?",
                    new byte[32],
                    challenge.challengeId()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    String code = sentCodes.get(challenge.challengeId());
    assertThat(code).matches("[0-9]{6}");

    MvcResult verified =
        mvc.perform(
                post(verifyPath(challenge.challengeId()))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + code + "\"}"))
            .andExpect(status().isNoContent())
            .andReturn();
    String setCookie = verified.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).contains("AGDCLIENT=", "HttpOnly", "SameSite=Lax", "Path=/api/v1/client");
    String token = setCookie.substring("AGDCLIENT=".length(), setCookie.indexOf(';'));
    org.mockito.Mockito.when(clock.instant()).thenReturn(now.plusSeconds(899));
    mvc.perform(get("/api/v1/client/session").header("Cookie", "AGDCLIENT=" + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(true));
    String storedDigest =
        appJdbc.queryForObject(
            "SELECT encode(session_digest, 'hex') FROM agendamento.client_access_session WHERE tenant_id = ?",
            String.class,
            tenantId);
    assertThat(storedDigest).hasSize(64);

    mvc.perform(
            post(verifyPath(unknownChallenge.challengeId()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + sentCodes.get(unknownChallenge.challengeId()) + "\"}"))
        .andExpect(status().isUnauthorized());
    assertThat(count("client_access_session")).isEqualTo(1);

    org.mockito.Mockito.when(clock.instant()).thenReturn(now.plusSeconds(900));
    assertThat(service.isAuthenticated("AGDCLIENT=" + token)).isFalse();
  }

  @Test
  void fourthSendStartsThirtyMinuteBlockWithoutSendingAndRetryDoesNotConsumeQuota()
      throws Exception {
    List<UUID> challenges = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      MvcResult result = request(PHONE, "access-limit-" + UUID.randomUUID());
      assertThat(result.getResponse().getStatus()).isEqualTo(202);
      challenges.add(
          mapper
              .readValue(result.getResponse().getContentAsString(), AccessChallengeAccepted.class)
              .challengeId());
    }
    assertThat(sentCodes.get(challenges.get(0))).isNotNull();
    assertThat(sentCodes.get(challenges.get(1))).isNotNull();
    assertThat(sentCodes.get(challenges.get(2))).isNotNull();
    assertThat(sentCodes.get(challenges.get(3))).isNull();
    assertThat(count("client_access_send_intent")).isEqualTo(3);
    assertThat(
            ownerJdbc.queryForObject(
                "SELECT blocked_until > ? FROM agendamento.client_access_phone_limit WHERE tenant_id = ?",
                Boolean.class,
                java.sql.Timestamp.from(now),
                tenantId))
        .isTrue();
    request(PHONE, "access-limit-retry-" + UUID.randomUUID());
    assertThat(count("client_access_send_intent")).isEqualTo(3);
    org.mockito.Mockito.when(clock.instant()).thenReturn(now.plusSeconds(1_800));
    request(PHONE, "access-limit-expired-" + UUID.randomUUID());
    assertThat(count("client_access_send_intent")).isEqualTo(4);
  }

  @Test
  void sendWindowExcludesIntentExactlyFifteenMinutesOld() throws Exception {
    request(PHONE, "access-window-boundary-first-" + UUID.randomUUID());
    org.mockito.Mockito.when(clock.instant()).thenReturn(now.plusSeconds(900));
    request(PHONE, "access-window-boundary-second-" + UUID.randomUUID());
    request(PHONE, "access-window-boundary-third-" + UUID.randomUUID());
    request(PHONE, "access-window-boundary-fourth-" + UUID.randomUUID());

    assertThat(count("client_access_send_intent")).isEqualTo(4);
    assertThat(
            ownerJdbc.queryForObject(
                "SELECT blocked_until IS NULL FROM agendamento.client_access_phone_limit WHERE tenant_id = ?",
                Boolean.class,
                tenantId))
        .isTrue();
  }

  @Test
  void fifthWrongCodeCommitsChallengeBlockAndExactExpiryIsInvalid() throws Exception {
    MvcResult response = request(PHONE, "access-attempts-" + UUID.randomUUID());
    UUID challengeId =
        mapper
            .readValue(response.getResponse().getContentAsString(), AccessChallengeAccepted.class)
            .challengeId();
    String actual = sentCodes.get(challengeId);
    String wrong = "000000".equals(actual) ? "000001" : "000000";
    for (int index = 0; index < 5; index++) {
      mvc.perform(
              post(verifyPath(challengeId))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"code\":\"" + wrong + "\"}"))
          .andExpect(status().isUnauthorized());
    }
    assertThat(
            ownerJdbc.queryForObject(
                "SELECT status FROM agendamento.client_access_challenge WHERE challenge_id = ?",
                String.class,
                challengeId))
        .isEqualTo("BLOCKED");
    assertThat(count("client_access_session")).isZero();

    MvcResult fresh = request(PHONE, "access-expiry-" + UUID.randomUUID());
    UUID expires =
        mapper
            .readValue(fresh.getResponse().getContentAsString(), AccessChallengeAccepted.class)
            .challengeId();
    String expiresCode = sentCodes.get(expires);
    org.mockito.Mockito.when(clock.instant()).thenReturn(now.plusSeconds(300));
    mvc.perform(
            post(verifyPath(expires))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + expiresCode + "\"}"))
        .andExpect(status().isUnauthorized());
    assertThat(
            ownerJdbc.queryForObject(
                "SELECT status FROM agendamento.client_access_challenge WHERE challenge_id = ?",
                String.class,
                expires))
        .isEqualTo("EXPIRED");
  }

  @Test
  void concurrentVerificationConsumesChallengeOnlyOnce() throws Exception {
    MvcResult response = request(PHONE, "access-race-" + UUID.randomUUID());
    UUID challengeId =
        mapper
            .readValue(response.getResponse().getContentAsString(), AccessChallengeAccepted.class)
            .challengeId();
    String code = sentCodes.get(challengeId);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> verify =
          () -> {
            try {
              service.verify(slug, challengeId, code);
              return true;
            } catch (AccessChallengeInvalidException exception) {
              return false;
            }
          };
      Future<Boolean> first = executor.submit(verify);
      Future<Boolean> second = executor.submit(verify);
      assertThat(List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count())
          .isEqualTo(1);
    }
    assertThat(count("client_access_session")).isEqualTo(1);
    assertThat(
            ownerJdbc.queryForObject(
                "SELECT status FROM agendamento.client_access_challenge WHERE challenge_id = ?",
                String.class,
                challengeId))
        .isEqualTo("VERIFIED");
  }

  @Test
  void requestRetryKeepsOriginalChallengeAndDoesNotSendAgain() throws Exception {
    String key = "access-replay-" + UUID.randomUUID();
    UUID first =
        mapper
            .readValue(
                request(PHONE, key).getResponse().getContentAsString(),
                AccessChallengeAccepted.class)
            .challengeId();
    UUID replay =
        mapper
            .readValue(
                request(PHONE, key).getResponse().getContentAsString(),
                AccessChallengeAccepted.class)
            .challengeId();

    assertThat(replay).isEqualTo(first);
    assertThat(count("client_access_request_intent")).isEqualTo(1);
    assertThat(count("client_access_send_intent")).isEqualTo(1);
    verify(sender, times(1)).send(any(), any(), any(), any());
  }

  @Test
  void oversizedPhoneIsDefinitiveValidationErrorAndCorrectedPhoneCanBeSubmitted() throws Exception {
    String key = "access-phone-size-" + UUID.randomUUID();
    String oversizedPhone = "+" + "1".repeat(32);

    mvc.perform(
            post("/api/v1/public/" + slug + "/access-challenges")
                .with(csrf())
                .header("Idempotency-Key", "short")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("phone", PHONE))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

    mvc.perform(
            post("/api/v1/public/" + slug + "/access-challenges")
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("phone", oversizedPhone))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_PHONE"));
    assertThat(count("client_access_challenge")).isZero();

    MvcResult corrected = request(PHONE, key);
    assertThat(corrected.getResponse().getStatus()).isEqualTo(202);
    assertThat(count("client_access_challenge")).isEqualTo(1);
    verify(sender, times(1)).send(any(), any(), any(), any());
  }

  @Test
  void concurrentSameRequestIntentCreatesOneChallengeAndOneDelivery() throws Exception {
    String key = "access-concurrent-replay-" + UUID.randomUUID();
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<UUID> request = () -> service.requestChallenge(slug, PHONE, key).challengeId();
      Future<UUID> first = executor.submit(request);
      Future<UUID> second = executor.submit(request);

      assertThat(first.get()).isEqualTo(second.get());
    }
    assertThat(count("client_access_challenge")).isEqualTo(1);
    assertThat(count("client_access_send_intent")).isEqualTo(1);
    verify(sender, times(1)).send(any(), any(), any(), any());
  }

  @Test
  void transportUnknownIsNotAutomaticallyRetriedOnSameIntent() throws Exception {
    String key = "access-unknown-delivery-" + UUID.randomUUID();
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic transport timeout"))
        .when(sender)
        .send(any(), any(), any(), any());

    UUID original =
        mapper
            .readValue(
                request(PHONE, key).getResponse().getContentAsString(),
                AccessChallengeAccepted.class)
            .challengeId();
    UUID replay =
        mapper
            .readValue(
                request(PHONE, key).getResponse().getContentAsString(),
                AccessChallengeAccepted.class)
            .challengeId();

    assertThat(replay).isEqualTo(original);
    assertThat(
            appJdbc.queryForObject(
                "SELECT delivery_status FROM agendamento.client_access_challenge WHERE challenge_id = ?",
                String.class,
                original))
        .isEqualTo("UNKNOWN");
    verify(sender, times(1)).send(any(), any(), any(), any());
    assertThat(count("client_access_send_intent")).isEqualTo(1);
  }

  @Test
  void reusedIntentWithDifferentPhoneIsRejectedWithoutCreatingAnotherChallenge() throws Exception {
    String key = "access-conflict-" + UUID.randomUUID();
    request(PHONE, key);

    assertThatThrownBy(() -> service.requestChallenge(slug, "+5511999991124", key))
        .isInstanceOf(
            com.appagendamento.clientaccess.application.IdempotencyKeyConflictException.class);
    assertThat(count("client_access_challenge")).isEqualTo(1);
    assertThat(count("client_access_send_intent")).isEqualTo(1);
  }

  @Test
  void challengeCannotBeUsedThroughAnotherTenantAndInvalidCookieDoesNotAuthenticate()
      throws Exception {
    UUID otherTenantId = UUID.randomUUID();
    String otherSlug = "client-access-other-" + UUID.randomUUID().toString().substring(0, 8);
    ownerJdbc.update(
        "INSERT INTO agendamento.tenant(tenant_id, name, slug, timezone, status) VALUES (?, 'Other access fixture', ?, 'UTC', 'ACTIVE')",
        otherTenantId,
        otherSlug);
    ownerJdbc.update(
        "INSERT INTO agendamento.client(client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Other client', ?)",
        UUID.randomUUID(),
        otherTenantId,
        PHONE);
    UUID challengeId =
        mapper
            .readValue(
                request(PHONE, "access-tenant-" + UUID.randomUUID())
                    .getResponse()
                    .getContentAsString(),
                AccessChallengeAccepted.class)
            .challengeId();

    mvc.perform(
            post("/api/v1/public/" + otherSlug + "/access-challenges/" + challengeId + "/verify")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"000000\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/client/session").header("Cookie", "AGDCLIENT=invalid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(false));
    assertThat(count("client_access_session")).isZero();
  }

  @Test
  void challengeBoundRecoveryRejectsAnOlderSessionForAnotherPhoneOrTenant() throws Exception {
    UUID firstChallenge =
        requestChallenge(slug, PHONE, "access-recovery-session-" + UUID.randomUUID());
    String firstCode = sentCodes.get(firstChallenge);
    MvcResult verified =
        mvc.perform(
                post(verifyPath(firstChallenge))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + firstCode + "\"}"))
            .andExpect(status().isNoContent())
            .andReturn();
    String token =
        verified
            .getResponse()
            .getHeader("Set-Cookie")
            .substring(
                "AGDCLIENT=".length(), verified.getResponse().getHeader("Set-Cookie").indexOf(';'));
    String cookie = "AGDCLIENT=" + token;

    String otherPhone = "+5511999991125";
    ownerJdbc.update(
        "INSERT INTO agendamento.client(client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Other phone', ?)",
        UUID.randomUUID(),
        tenantId,
        otherPhone);
    UUID sameTenantChallenge =
        requestChallenge(slug, otherPhone, "access-recovery-other-phone-" + UUID.randomUUID());
    rejectWithWrongCode(sameTenantChallenge, slug);
    assertBoundSession(cookie, slug, sameTenantChallenge, false);
    mvc.perform(get("/api/v1/client/session").param("slug", slug).header("Cookie", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(false));
    mvc.perform(
            get("/api/v1/client/session")
                .param("challengeId", sameTenantChallenge.toString())
                .header("Cookie", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(false));

    UUID otherTenantId = UUID.randomUUID();
    String otherSlug = "client-access-recovery-" + UUID.randomUUID().toString().substring(0, 8);
    ownerJdbc.update(
        "INSERT INTO agendamento.tenant(tenant_id, name, slug, timezone, status) VALUES (?, 'Other recovery fixture', ?, 'UTC', 'ACTIVE')",
        otherTenantId,
        otherSlug);
    String otherTenantPhone = "+5511999991126";
    ownerJdbc.update(
        "INSERT INTO agendamento.client(client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Other tenant phone', ?)",
        UUID.randomUUID(),
        otherTenantId,
        otherTenantPhone);
    UUID otherTenantChallenge =
        requestChallenge(
            otherSlug, otherTenantPhone, "access-recovery-other-tenant-" + UUID.randomUUID());
    rejectWithWrongCode(otherTenantChallenge, otherSlug);
    assertBoundSession(cookie, otherSlug, otherTenantChallenge, false);

    mvc.perform(get("/api/v1/client/session").header("Cookie", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(true));
    assertThat(count("client_access_session")).isEqualTo(1);
  }

  @Test
  void clientAreaScopesListDetailCursorAndCancellationToVerifiedPhone() throws Exception {
    UUID appointmentId = insertClientAppointment(PHONE, now.plusSeconds(7_200));
    UUID otherClientId = UUID.randomUUID();
    String otherPhone = "+5511999991188";
    ownerJdbc.update(
        "INSERT INTO agendamento.client(client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Outro cliente', ?)",
        otherClientId,
        tenantId,
        otherPhone);
    UUID otherAppointmentId = insertAppointmentForClient(otherClientId, now.plusSeconds(10_800));
    UUID foreignTenantAppointmentId =
        insertForeignTenantAppointment(PHONE, now.plusSeconds(14_400));
    String cookie = verifiedClientCookie(slug, PHONE);

    mvc.perform(get("/api/v1/client/appointments").header("Cookie", cookie))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].appointmentId").value(appointmentId.toString()))
        .andExpect(jsonPath("$.items[0].status").value("CONFIRMED"))
        .andExpect(jsonPath("$.items[0].clientName").doesNotExist())
        .andExpect(jsonPath("$.items[0].phone").doesNotExist());
    mvc.perform(get("/api/v1/client/appointments/{id}", appointmentId).header("Cookie", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.appointmentId").value(appointmentId.toString()));
    mvc.perform(
            get("/api/v1/client/appointments/{id}", otherAppointmentId).header("Cookie", cookie))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    mvc.perform(
            get("/api/v1/client/appointments/{id}", foreignTenantAppointmentId)
                .header("Cookie", cookie))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    mvc.perform(get("/api/v1/client/appointments/{id}", UUID.randomUUID()).header("Cookie", cookie))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    mvc.perform(
            get("/api/v1/client/appointments")
                .header("Cookie", cookie)
                .param("cursor", otherAppointmentId.toString()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/client/appointments/{id}/cancel", otherAppointmentId)
                .with(csrf())
                .header("Cookie", cookie)
                .header("Idempotency-Key", "foreign-cancel-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isNotFound());
    assertThat(statusOf(otherAppointmentId)).isEqualTo("CONFIRMED");
    assertThat(countAppointmentsFor(otherClientId)).isEqualTo(1);
  }

  @Test
  void activeBookingRestrictionDoesNotBlockClientAppointmentAccessOrAllowedCancellation()
      throws Exception {
    UUID noShowAppointment = insertClientAppointment(PHONE, now.minusSeconds(86_400));
    UUID cancellableAppointment = insertClientAppointment(PHONE, now.plusSeconds(7_200));
    UUID clientId =
        ownerJdbc.queryForObject(
            "SELECT client_id FROM agendamento.client WHERE tenant_id = ? AND normalized_phone = ?",
            UUID.class,
            tenantId,
            PHONE);
    UUID noShowHistoryId = UUID.randomUUID();
    ownerJdbc.update(
        "UPDATE agendamento.appointment SET status = 'NO_SHOW', version = 2 WHERE tenant_id = ? AND appointment_id = ?",
        tenantId,
        noShowAppointment);
    ownerJdbc.update(
        "INSERT INTO agendamento.appointment_history(history_id, tenant_id, appointment_id, from_status, to_status, actor_type, actor_ref, reason_code, occurred_at, version_before, version_after) VALUES (?, ?, ?, 'CONFIRMED', 'NO_SHOW', 'ADMIN', 'TENANT_ADMIN', 'APPOINTMENT_NO_SHOW', ?, 1, 2)",
        noShowHistoryId,
        tenantId,
        noShowAppointment,
        java.sql.Timestamp.from(now.minusSeconds(86_000)));
    UUID restrictionId = UUID.randomUUID();
    ownerJdbc.update(
        "INSERT INTO agendamento.client_booking_restriction(restriction_id, tenant_id, client_id, source_appointment_id, source_history_id, status, evaluated_at, count_at_activation, threshold_at_activation, policy_version, version, created_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, 1, 1, 1, 1, ?)",
        restrictionId,
        tenantId,
        clientId,
        noShowAppointment,
        noShowHistoryId,
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now));
    ownerJdbc.update(
        "INSERT INTO agendamento.client_booking_restriction_history(history_id, tenant_id, restriction_id, event_type, actor_type, occurred_at, count_at_decision, threshold_at_decision, policy_version, restriction_version) VALUES (?, ?, ?, 'ACTIVATED', 'SYSTEM', ?, 1, 1, 1, 1)",
        UUID.randomUUID(),
        tenantId,
        restrictionId,
        java.sql.Timestamp.from(now));
    String cookie = verifiedClientCookie(slug, PHONE);

    mvc.perform(get("/api/v1/client/appointments").header("Cookie", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2));
    mvc.perform(
            get("/api/v1/client/appointments/{id}", cancellableAppointment)
                .header("Cookie", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
    mvc.perform(
            post("/api/v1/client/appointments/{id}/cancel", cancellableAppointment)
                .with(csrf())
                .header("Cookie", cookie)
                .header("Idempotency-Key", "restricted-client-cancel-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED_BY_CLIENT"));

    assertThat(statusOf(cancellableAppointment)).isEqualTo("CANCELLED_BY_CLIENT");
    assertThat(
            ownerJdbc.queryForObject(
                "SELECT status FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND restriction_id = ?",
                String.class,
                tenantId,
                restrictionId))
        .isEqualTo("ACTIVE");
  }

  @Test
  void clientCancellationAtInclusiveCutoffWorksForInactiveServiceAndReplayHasNoEffects()
      throws Exception {
    UUID appointmentId = insertClientAppointment(PHONE, now.plusSeconds(7_200));
    String cookie = verifiedClientCookie(slug, PHONE);
    String idempotencyKey = "client-cancel-" + UUID.randomUUID();

    mvc.perform(
            post("/api/v1/client/appointments/{id}/cancel", appointmentId)
                .header("Cookie", cookie)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isForbidden());
    assertThat(statusOf(appointmentId)).isEqualTo("CONFIRMED");

    mvc.perform(
            post("/api/v1/client/appointments/{id}/cancel", appointmentId)
                .with(csrf())
                .header("Cookie", cookie)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.appointmentId").value(appointmentId.toString()))
        .andExpect(jsonPath("$.status").value("CANCELLED_BY_CLIENT"))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Cache-Control", "no-store"));

    UUID operationId =
        UUID.fromString(
            appJdbc.queryForObject(
                "SELECT operation_id FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope LIKE 'appointments.client.cancel.%'",
                String.class, tenantId));
    ownerJdbc.update(
        "UPDATE agendamento.client_access_session SET expires_at = ? WHERE tenant_id = ?",
        java.sql.Timestamp.from(now.plusSeconds(14_400)),
        tenantId);
    org.mockito.Mockito.when(clock.instant()).thenReturn(now.plusSeconds(7_200));
    mvc.perform(
            post("/api/v1/client/appointments/{id}/cancel", appointmentId)
                .with(csrf())
                .header("Cookie", cookie)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.appointmentId").value(appointmentId.toString()))
        .andExpect(jsonPath("$.version").value(2));

    assertThat(statusOf(appointmentId)).isEqualTo("CANCELLED_BY_CLIENT");
    assertThat(versionOf(appointmentId)).isEqualTo(2);
    assertThat(countHistory(appointmentId)).isEqualTo(2);
    assertThat(countAudit(appointmentId)).isEqualTo(1);
    assertThat(countOutbox(appointmentId)).isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE operation_id = ? AND state = 'COMPLETED'",
                Long.class,
                operationId))
        .isEqualTo(1);
    mvc.perform(get("/api/v1/client/appointments").header("Cookie", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].status").value("CANCELLED_BY_CLIENT"));
  }

  @Test
  void expiredClientCookieCannotReadOrMutateClientAppointments() throws Exception {
    UUID appointmentId = insertClientAppointment(PHONE, now.plusSeconds(7_200));
    String cookie = verifiedClientCookie(slug, PHONE);
    org.mockito.Mockito.when(clock.instant()).thenReturn(now.plusSeconds(900));

    mvc.perform(get("/api/v1/client/appointments").header("Cookie", cookie))
        .andExpect(status().isUnauthorized())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Cache-Control", "no-store"));
    mvc.perform(
            post("/api/v1/client/appointments/{id}/cancel", appointmentId)
                .with(csrf())
                .header("Cookie", cookie)
                .header("Idempotency-Key", "expired-cancel-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isUnauthorized());
    assertThat(statusOf(appointmentId)).isEqualTo("CONFIRMED");
    assertThat(versionOf(appointmentId)).isEqualTo(1);
    assertThat(countHistory(appointmentId)).isEqualTo(1);
    assertThat(countAudit(appointmentId)).isZero();
    assertThat(countOutbox(appointmentId)).isZero();
  }

  @Test
  void revokedClientCookieCannotReadOrMutateClientAppointments() throws Exception {
    UUID appointmentId = insertClientAppointment(PHONE, now.plusSeconds(7_200));
    String cookie = verifiedClientCookie(slug, PHONE);

    mvc.perform(post("/api/v1/client/session/logout").with(csrf()).header("Cookie", cookie))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/client/appointments").header("Cookie", cookie))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/client/appointments/{id}/cancel", appointmentId)
                .with(csrf())
                .header("Cookie", cookie)
                .header("Idempotency-Key", "revoked-cancel-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isUnauthorized());
    assertThat(statusOf(appointmentId)).isEqualTo("CONFIRMED");
    assertThat(versionOf(appointmentId)).isEqualTo(1);
    assertThat(countHistory(appointmentId)).isEqualTo(1);
    assertThat(countAudit(appointmentId)).isZero();
    assertThat(countOutbox(appointmentId)).isZero();
  }

  @Test
  void cancellationAfterCutoffIsRejectedWithoutSuccessfulEffects() throws Exception {
    UUID appointmentId = insertClientAppointment(PHONE, now.plusSeconds(3_600));
    String cookie = verifiedClientCookie(slug, PHONE);

    mvc.perform(
            post("/api/v1/client/appointments/{id}/cancel", appointmentId)
                .with(csrf())
                .header("Cookie", cookie)
                .header("Idempotency-Key", "late-cancel-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("CANCELLATION_WINDOW_CLOSED"));
    assertThat(statusOf(appointmentId)).isEqualTo("CONFIRMED");
    assertThat(versionOf(appointmentId)).isEqualTo(1);
    assertThat(countHistory(appointmentId)).isEqualTo(1);
    assertThat(countAudit(appointmentId)).isZero();
    assertThat(countOutbox(appointmentId)).isZero();
  }

  @Test
  void clientCancellationAuditFailureRollsBackStateHistoryOutboxAndReceipt() throws Exception {
    UUID appointmentId = insertClientAppointment(PHONE, now.plusSeconds(7_200));
    String cookie = verifiedClientCookie(slug, PHONE);
    String key = "client-cancel-audit-failure-" + UUID.randomUUID();
    ownerJdbc.execute(
        "CREATE FUNCTION agendamento.fail_client_cancel_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action = 'APPOINTMENT_CANCELLED_BY_CLIENT' THEN RAISE EXCEPTION 'injected client audit failure'; END IF; RETURN NEW; END $$");
    ownerJdbc.execute(
        "CREATE TRIGGER fail_client_cancel_audit BEFORE INSERT ON agendamento.audit_event FOR EACH ROW EXECUTE FUNCTION agendamento.fail_client_cancel_audit()");
    try {
      assertThatThrownBy(
              () ->
                  mvc.perform(
                          post("/api/v1/client/appointments/{id}/cancel", appointmentId)
                              .with(csrf())
                              .header("Cookie", cookie)
                              .header("Idempotency-Key", key)
                              .contentType(MediaType.APPLICATION_JSON)
                              .content("{\"expectedVersion\":1}"))
                      .andReturn())
          .hasStackTraceContaining("injected client audit failure");

      assertThat(statusOf(appointmentId)).isEqualTo("CONFIRMED");
      assertThat(versionOf(appointmentId)).isEqualTo(1);
      assertThat(countHistory(appointmentId)).isEqualTo(1);
      assertThat(countAudit(appointmentId)).isZero();
      assertThat(countOutbox(appointmentId)).isZero();
      assertThat(
              ownerJdbc.queryForObject(
                  "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope LIKE 'appointments.client.cancel.%'",
                  Long.class, tenantId))
          .isZero();
    } finally {
      ownerJdbc.execute("DROP TRIGGER fail_client_cancel_audit ON agendamento.audit_event");
      ownerJdbc.execute("DROP FUNCTION agendamento.fail_client_cancel_audit()");
    }

    mvc.perform(
            post("/api/v1/client/appointments/{id}/cancel", appointmentId)
                .with(csrf())
                .header("Cookie", cookie)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED_BY_CLIENT"));
    assertThat(statusOf(appointmentId)).isEqualTo("CANCELLED_BY_CLIENT");
    assertThat(versionOf(appointmentId)).isEqualTo(2);
    assertThat(countHistory(appointmentId)).isEqualTo(2);
    assertThat(countAudit(appointmentId)).isEqualTo(1);
    assertThat(countOutbox(appointmentId)).isEqualTo(1);
  }

  @Test
  void clientAppointmentListUsesBoundedDeterministicCursorPages() throws Exception {
    Instant firstStart = now.plusSeconds(86_400);
    for (int index = 0; index < 21; index++) {
      insertClientAppointment(PHONE, firstStart.plusSeconds(index * 3_600L));
    }
    String cookie = verifiedClientCookie(slug, PHONE);

    MvcResult firstResult =
        mvc.perform(get("/api/v1/client/appointments").header("Cookie", cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(20))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andReturn();
    var firstPage = mapper.readTree(firstResult.getResponse().getContentAsString());
    String cursor = firstPage.get("nextCursor").asText();
    MvcResult nextResult =
        mvc.perform(
                get("/api/v1/client/appointments").header("Cookie", cookie).param("cursor", cursor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andReturn();
    var nextPage = mapper.readTree(nextResult.getResponse().getContentAsString());
    assertThat(nextPage.at("/items/0/appointmentId").asText())
        .isNotEqualTo(firstPage.at("/items/19/appointmentId").asText());
    assertThat(Instant.parse(nextPage.at("/items/0/startsAt").asText()))
        .isAfter(Instant.parse(firstPage.at("/items/19/startsAt").asText()));
  }

  @Test
  void clientCursorKeepsOriginalBoundaryWhenMarkerMovesForwardOrBackward() throws Exception {
    Instant firstStart = now.plusSeconds(86_400);
    List<UUID> appointmentIds = new ArrayList<>();
    for (int index = 0; index < 21; index++) {
      appointmentIds.add(insertClientAppointment(PHONE, firstStart.plusSeconds(index * 3_600L)));
    }
    String cookie = verifiedClientCookie(slug, PHONE);

    var forwardFirst = clientPage(cookie, null);
    UUID forwardMarker = UUID.fromString(forwardFirst.at("/items/19/appointmentId").asText());
    UUID forwardRemaining = UUID.fromString(forwardFirst.at("/items/0/appointmentId").asText());
    Instant originalForwardBoundary = Instant.parse(forwardFirst.at("/items/19/startsAt").asText());
    UUID remainingAppointment = appointmentIds.get(20);
    Instant remainingStartsAt = firstStart.plusSeconds(20 * 3_600L);
    moveClientAppointment(forwardMarker, remainingStartsAt.plusSeconds(3_600));

    var forwardNext = clientPage(cookie, forwardFirst.get("nextCursor").asText());
    assertThat(forwardNext.at("/items/0/appointmentId").asText())
        .isEqualTo(remainingAppointment.toString());
    assertThat(forwardNext.at("/items/0/appointmentId").asText())
        .isNotEqualTo(forwardRemaining.toString());
    assertThat(Instant.parse(forwardNext.at("/items/0/startsAt").asText()))
        .isEqualTo(remainingStartsAt);
    assertThat(originalForwardBoundary).isBefore(remainingStartsAt);

    moveClientAppointment(forwardMarker, firstStart.plusSeconds(19 * 3_600L));
    var backwardFirst = clientPage(cookie, null);
    UUID backwardMarker = UUID.fromString(backwardFirst.at("/items/19/appointmentId").asText());
    UUID backwardRemaining = appointmentIds.get(20);
    Instant originalBackwardBoundary =
        Instant.parse(backwardFirst.at("/items/19/startsAt").asText());
    moveClientAppointment(backwardMarker, firstStart.minusSeconds(3_600));

    var backwardNext = clientPage(cookie, backwardFirst.get("nextCursor").asText());
    assertThat(backwardNext.at("/items/0/appointmentId").asText())
        .isEqualTo(backwardRemaining.toString());
    assertThat(Instant.parse(backwardNext.at("/items/0/startsAt").asText()))
        .isEqualTo(firstStart.plusSeconds(20 * 3_600L));
    assertThat(originalBackwardBoundary)
        .isBefore(Instant.parse(backwardNext.at("/items/0/startsAt").asText()));
  }

  @Test
  void clientCursorFromAnotherAuthenticatedOwnerIsRejected() throws Exception {
    String otherPhone = "+5511999991189";
    UUID otherClientId = UUID.randomUUID();
    ownerJdbc.update(
        "INSERT INTO agendamento.client(client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Outro cursor', ?)",
        otherClientId,
        tenantId,
        otherPhone);
    Instant firstStart = now.plusSeconds(86_400);
    insertClientAppointment(PHONE, firstStart.minusSeconds(3_600));
    for (int index = 0; index < 21; index++) {
      insertAppointmentForClient(otherClientId, firstStart.plusSeconds(index * 3_600L));
    }
    String otherCookie = verifiedClientCookie(slug, otherPhone);
    String foreignCursor = clientPage(otherCookie, null).get("nextCursor").asText();
    String ownCookie = verifiedClientCookie(slug, PHONE);

    mvc.perform(
            get("/api/v1/client/appointments")
                .header("Cookie", ownCookie)
                .param("cursor", foreignCursor))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  private tools.jackson.databind.JsonNode clientPage(String cookie, String cursor)
      throws Exception {
    var request = get("/api/v1/client/appointments").header("Cookie", cookie);
    if (cursor != null) request.param("cursor", cursor);
    MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
    return mapper.readTree(result.getResponse().getContentAsString());
  }

  private void moveClientAppointment(UUID appointmentId, Instant startsAt) {
    ownerJdbc.update(
        "UPDATE agendamento.appointment SET starts_at = ?, service_ends_at = ?, occupied_until = ? WHERE tenant_id = ? AND appointment_id = ?",
        java.sql.Timestamp.from(startsAt),
        java.sql.Timestamp.from(startsAt.plusSeconds(1_800)),
        java.sql.Timestamp.from(startsAt.plusSeconds(1_800)),
        tenantId,
        appointmentId);
  }

  @Test
  void verificationUsesClockInstantAfterWaitingForChallengeRowLock() throws Exception {
    UUID challengeId = requestChallenge(slug, PHONE, "access-expiry-lock-" + UUID.randomUUID());
    String code = sentCodes.get(challengeId);
    Instant expiresAt = now.plusSeconds(300);
    ownerJdbc.update(
        "UPDATE agendamento.client_access_challenge SET expires_at = ? WHERE tenant_id = ? AND challenge_id = ?",
        java.sql.Timestamp.from(expiresAt),
        tenantId,
        challengeId);

    try (var lockConnection = PostgresTestEnvironment.migrationDataSource().getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      lockConnection.setAutoCommit(false);
      try (var lock =
          lockConnection.prepareStatement(
              "SELECT challenge_id FROM agendamento.client_access_challenge WHERE tenant_id = ? AND challenge_id = ? FOR UPDATE")) {
        lock.setObject(1, tenantId);
        lock.setObject(2, challengeId);
        lock.executeQuery().close();
      }

      CountDownLatch verifyStarted = new CountDownLatch(1);
      Future<?> verification =
          executor.submit(
              () -> {
                verifyStarted.countDown();
                service.verify(slug, challengeId, code);
              });
      assertThat(verifyStarted.await(5, TimeUnit.SECONDS)).isTrue();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      boolean waitingForLock = false;
      while (System.nanoTime() < deadline && !waitingForLock) {
        waitingForLock =
            Boolean.TRUE.equals(
                ownerJdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock' AND query ILIKE '%client_access_challenge%FOR UPDATE%')",
                    Boolean.class));
        if (!waitingForLock) Thread.sleep(20);
      }
      assertThat(waitingForLock).as("verify must wait on the locked challenge row").isTrue();
      now = expiresAt;
      lockConnection.commit();

      assertThatThrownBy(verification::get)
          .hasCauseInstanceOf(AccessChallengeInvalidException.class);
    }
    assertThat(
            ownerJdbc.queryForObject(
                "SELECT status FROM agendamento.client_access_challenge WHERE tenant_id = ? AND challenge_id = ?",
                String.class,
                tenantId,
                challengeId))
        .isEqualTo("EXPIRED");
    assertThat(count("client_access_session")).isZero();
  }

  @Test
  void csrfIsRequiredForChallengeMutation() throws Exception {
    mvc.perform(
            post("/api/v1/public/" + slug + "/access-challenges")
                .header("Idempotency-Key", "access-no-csrf-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + PHONE + "\"}"))
        .andExpect(status().isForbidden());
    assertThat(count("client_access_challenge")).isZero();
  }

  @Test
  void concurrentRequestsSharePersistedSendLimit() throws Exception {
    try (var executor = Executors.newFixedThreadPool(4)) {
      List<Callable<UUID>> requests = new ArrayList<>();
      for (int index = 0; index < 4; index++) {
        String key = "access-concurrent-" + UUID.randomUUID();
        requests.add(() -> service.requestChallenge(slug, PHONE, key).challengeId());
      }
      List<Future<UUID>> results = executor.invokeAll(requests);
      List<UUID> challengeIds = new ArrayList<>();
      for (Future<UUID> result : results) challengeIds.add(result.get());
      assertThat(challengeIds).hasSize(4).doesNotHaveDuplicates();
    }
    assertThat(count("client_access_send_intent")).isEqualTo(3);
    assertThat(
            ownerJdbc.queryForObject(
                "SELECT blocked_until > ? FROM agendamento.client_access_phone_limit WHERE tenant_id = ?",
                Boolean.class,
                java.sql.Timestamp.from(now),
                tenantId))
        .isTrue();
    verify(sender, times(3)).send(any(), any(), any(), any());
  }

  private MvcResult request(String phone, String key) throws Exception {
    return request(slug, phone, key);
  }

  private MvcResult request(String requestSlug, String phone, String key) throws Exception {
    return mvc.perform(
            post("/api/v1/public/" + requestSlug + "/access-challenges")
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.challengeAccepted").value(true))
        .andReturn();
  }

  private UUID requestChallenge(String requestSlug, String phone, String key) throws Exception {
    return mapper
        .readValue(
            request(requestSlug, phone, key).getResponse().getContentAsString(),
            AccessChallengeAccepted.class)
        .challengeId();
  }

  private void rejectWithWrongCode(UUID challengeId, String requestSlug) throws Exception {
    String actual = sentCodes.get(challengeId);
    String wrong = "000000".equals(actual) ? "000001" : "000000";
    mvc.perform(
            post("/api/v1/public/" + requestSlug + "/access-challenges/" + challengeId + "/verify")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + wrong + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  private void assertBoundSession(
      String cookie, String requestSlug, UUID challengeId, boolean expected) throws Exception {
    mvc.perform(
            get("/api/v1/client/session")
                .param("slug", requestSlug)
                .param("challengeId", challengeId.toString())
                .header("Cookie", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(expected));
  }

  private String verifiedClientCookie(String requestSlug, String phone) throws Exception {
    UUID challengeId = requestChallenge(requestSlug, phone, "client-area-" + UUID.randomUUID());
    MvcResult result =
        mvc.perform(
                post("/api/v1/public/"
                        + requestSlug
                        + "/access-challenges/"
                        + challengeId
                        + "/verify")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + sentCodes.get(challengeId) + "\"}"))
            .andExpect(status().isNoContent())
            .andReturn();
    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).startsWith("AGDCLIENT=");
    return setCookie.substring(0, setCookie.indexOf(';'));
  }

  private UUID insertClientAppointment(String phone, Instant startsAt) {
    UUID clientId =
        ownerJdbc.queryForObject(
            "SELECT client_id FROM agendamento.client WHERE tenant_id = ? AND normalized_phone = ?",
            UUID.class,
            tenantId,
            phone);
    UUID serviceId = UUID.randomUUID();
    ownerJdbc.update(
        "INSERT INTO agendamento.service(service_id, tenant_id, name, normalized_name, duration_minutes, buffer_after_minutes, status, version) VALUES (?, ?, 'Consulta', 'consulta', 30, 0, 'INACTIVE', 2)",
        serviceId,
        tenantId);
    ownerJdbc.update(
        "INSERT INTO agendamento.availability_policy(tenant_id, slot_step_minutes, minimum_notice_hours, booking_horizon_days, cancellation_cutoff_hours, reminder_offset_hours, version) VALUES (?, 30, 2, 30, 2, 6, 1) ON CONFLICT (tenant_id) DO NOTHING",
        tenantId);
    return insertAppointment(clientId, serviceId, startsAt);
  }

  private UUID insertAppointmentForClient(UUID clientId, Instant startsAt) {
    UUID serviceId =
        ownerJdbc.queryForObject(
            "SELECT service_id FROM agendamento.service WHERE tenant_id = ? LIMIT 1",
            UUID.class,
            tenantId);
    return insertAppointment(clientId, serviceId, startsAt);
  }

  private UUID insertForeignTenantAppointment(String phone, Instant startsAt) {
    UUID otherTenantId = UUID.randomUUID();
    ownerJdbc.update(
        "INSERT INTO agendamento.tenant(tenant_id, name, slug, timezone, status) VALUES (?, 'Foreign fixture', ?, 'UTC', 'ACTIVE')",
        otherTenantId,
        "client-area-foreign-" + UUID.randomUUID().toString().substring(0, 8));
    UUID otherClientId = UUID.randomUUID();
    ownerJdbc.update(
        "INSERT INTO agendamento.client(client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Foreign fixture client', ?)",
        otherClientId,
        otherTenantId,
        phone);
    UUID otherServiceId = UUID.randomUUID();
    ownerJdbc.update(
        "INSERT INTO agendamento.service(service_id, tenant_id, name, normalized_name, duration_minutes, buffer_after_minutes, status, version) VALUES (?, ?, 'Consulta estrangeira', 'consulta estrangeira', 30, 0, 'ACTIVE', 1)",
        otherServiceId,
        otherTenantId);
    UUID appointmentId = UUID.randomUUID();
    Instant endsAt = startsAt.plusSeconds(1_800);
    ownerJdbc.update(
        "INSERT INTO agendamento.appointment(appointment_id, tenant_id, client_id, service_id, starts_at, service_ends_at, occupied_until, time_zone, client_name_snapshot, service_name_snapshot, service_duration_minutes, service_buffer_after_minutes, status, created_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, 'UTC', 'Foreign fixture client', 'Consulta estrangeira', 30, 0, 'CONFIRMED', ?, 1)",
        appointmentId,
        otherTenantId,
        otherClientId,
        otherServiceId,
        java.sql.Timestamp.from(startsAt),
        java.sql.Timestamp.from(endsAt),
        java.sql.Timestamp.from(endsAt),
        java.sql.Timestamp.from(now));
    return appointmentId;
  }

  private UUID insertAppointment(UUID clientId, UUID serviceId, Instant startsAt) {
    UUID appointmentId = UUID.randomUUID();
    Instant endsAt = startsAt.plusSeconds(1_800);
    ownerJdbc.update(
        "INSERT INTO agendamento.appointment(appointment_id, tenant_id, client_id, service_id, starts_at, service_ends_at, occupied_until, time_zone, client_name_snapshot, service_name_snapshot, service_duration_minutes, service_buffer_after_minutes, status, created_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, 'America/Sao_Paulo', 'Cliente Teste', 'Consulta histórica', 30, 0, 'CONFIRMED', ?, 1)",
        appointmentId,
        tenantId,
        clientId,
        serviceId,
        java.sql.Timestamp.from(startsAt),
        java.sql.Timestamp.from(endsAt),
        java.sql.Timestamp.from(endsAt),
        java.sql.Timestamp.from(now));
    ownerJdbc.update(
        "INSERT INTO agendamento.appointment_history(history_id, tenant_id, appointment_id, from_status, to_status, actor_type, actor_ref, reason_code, reason, occurred_at, previous_starts_at, previous_occupied_until, new_starts_at, new_occupied_until, version_before, version_after) VALUES (?, ?, ?, NULL, 'CONFIRMED', 'PUBLIC', 'PUBLIC_BOOKING', 'APPOINTMENT_CREATED', NULL, ?, NULL, NULL, NULL, NULL, NULL, NULL)",
        UUID.randomUUID(),
        tenantId,
        appointmentId,
        java.sql.Timestamp.from(now));
    return appointmentId;
  }

  private String statusOf(UUID appointmentId) {
    return appJdbc.queryForObject(
        "SELECT status FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
        String.class,
        tenantId,
        appointmentId);
  }

  private long versionOf(UUID appointmentId) {
    return appJdbc.queryForObject(
        "SELECT version FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
        Long.class,
        tenantId,
        appointmentId);
  }

  private long countAppointmentsFor(UUID clientId) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.appointment WHERE tenant_id = ? AND client_id = ?",
        Long.class,
        tenantId,
        clientId);
  }

  private long countHistory(UUID appointmentId) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.appointment_history WHERE tenant_id = ? AND appointment_id = ?",
        Long.class,
        tenantId,
        appointmentId);
  }

  private long countAudit(UUID appointmentId) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.audit_event WHERE tenant_id = ? AND target_id = ? AND action = 'APPOINTMENT_CANCELLED_BY_CLIENT'",
        Long.class,
        tenantId,
        appointmentId);
  }

  private long countOutbox(UUID appointmentId) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.outbox_event WHERE tenant_id = ? AND aggregate_id = ? AND event_type = 'AppointmentCancelled'",
        Long.class,
        tenantId,
        appointmentId);
  }

  private String verifyPath(UUID challengeId) {
    return "/api/v1/public/" + slug + "/access-challenges/" + challengeId + "/verify";
  }

  private long count(String table) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento." + table + " WHERE tenant_id = ?", Long.class, tenantId);
  }
}
