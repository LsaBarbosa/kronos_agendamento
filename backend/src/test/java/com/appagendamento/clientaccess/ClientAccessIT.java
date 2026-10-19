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

  private String verifyPath(UUID challengeId) {
    return "/api/v1/public/" + slug + "/access-challenges/" + challengeId + "/verify";
  }

  private long count(String table) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento." + table + " WHERE tenant_id = ?", Long.class, tenantId);
  }
}
