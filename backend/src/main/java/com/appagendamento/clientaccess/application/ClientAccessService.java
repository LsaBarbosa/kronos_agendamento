package com.appagendamento.clientaccess.application;

import com.appagendamento.clientaccess.api.AccessChallengeAccepted;
import com.appagendamento.clientaccess.api.AccessCodeSender;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ClientAccessService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ClientAccessService.class);

  private static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(5);
  private static final Duration SEND_WINDOW = Duration.ofMinutes(15);
  private static final Duration PHONE_BLOCK = Duration.ofMinutes(30);
  private static final Duration CHALLENGE_BLOCK = Duration.ofMinutes(5);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final ClientAccessCrypto crypto;
  private final AccessCodeSender sender;
  private final Clock clock;
  private final long sessionTtlSeconds;
  private final boolean cookieSecure;

  public ClientAccessService(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      ClientAccessCrypto crypto,
      AccessCodeSender sender,
      Clock clock,
      @Value("${app.client-access.session.ttl-seconds:0}") long sessionTtlSeconds,
      @Value("${app.client-access.session.cookie-secure:true}") boolean cookieSecure) {
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactionManager);
    this.crypto = crypto;
    this.sender = sender;
    this.sessionTtlSeconds = sessionTtlSeconds;
    this.cookieSecure = cookieSecure;
    this.clock = clock;
  }

  public AccessChallengeAccepted requestChallenge(String slug, String rawPhone, String key) {
    UUID tenantId = resolveTenant(slug);
    String phone = normalizePhone(rawPhone);
    UUID challengeId = UUID.randomUUID();
    Instant now = clock.instant();
    if (!crypto.configured() || !sessionConfigured() || !fingerprintVersionCompatible()) {
      return new AccessChallengeAccepted(true, (int) CHALLENGE_LIFETIME.toSeconds(), null);
    }
    byte[] phoneFingerprint = crypto.fingerprint(phone);
    byte[] intentDigest = crypto.digest(key);
    String code = crypto.newCode();
    byte[] salt = crypto.newSalt();
    byte[] verifier = crypto.verifier(code, salt, challengeId.toString(), tenantId.toString());
    RequestResult requestResult =
        transaction.execute(
            status -> {
              jdbc.update(
                  "INSERT INTO agendamento.client_access_request_intent(tenant_id, idempotency_digest, phone_fingerprint, fingerprint_key_id, created_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                  tenantId,
                  intentDigest,
                  phoneFingerprint,
                  crypto.fingerprintKeyId(),
                  Timestamp.from(now));
              List<byte[]> intentFingerprints =
                  jdbc.query(
                      "SELECT phone_fingerprint FROM agendamento.client_access_request_intent WHERE tenant_id = ? AND idempotency_digest = ?",
                      (row, index) -> row.getBytes(1),
                      tenantId,
                      intentDigest);
              if (intentFingerprints.isEmpty()
                  || !crypto.matches(intentFingerprints.getFirst(), phoneFingerprint)) {
                throw new IdempotencyKeyConflictException();
              }
              List<ChallengeRow> prior =
                  jdbc.query(
                      "SELECT challenge_id, phone_fingerprint FROM agendamento.client_access_challenge WHERE tenant_id = ? AND idempotency_digest = ?",
                      (row, index) ->
                          new ChallengeRow(row.getObject(1, UUID.class), row.getBytes(2)),
                      tenantId,
                      intentDigest);
              if (!prior.isEmpty()) {
                if (!crypto.matches(prior.getFirst().phoneFingerprint(), phoneFingerprint)) {
                  throw new IdempotencyKeyConflictException();
                }
                return new RequestResult(prior.getFirst().challengeId(), false, false);
              }
              jdbc.update(
                  "INSERT INTO agendamento.client_access_phone_limit(tenant_id, phone_fingerprint, fingerprint_key_id, window_started_at, send_count, updated_at) VALUES (?, ?, ?, ?, 0, ?) ON CONFLICT DO NOTHING",
                  tenantId,
                  phoneFingerprint,
                  crypto.fingerprintKeyId(),
                  Timestamp.from(now),
                  Timestamp.from(now));
              LimitRow limit =
                  jdbc.queryForObject(
                      "SELECT blocked_until FROM agendamento.client_access_phone_limit WHERE tenant_id = ? AND phone_fingerprint = ? FOR UPDATE",
                      (row, index) ->
                          new LimitRow(
                              row.getTimestamp(1) == null ? null : row.getTimestamp(1).toInstant()),
                      tenantId,
                      phoneFingerprint);
              long recentCount =
                  jdbc.queryForObject(
                      "SELECT count(*) FROM agendamento.client_access_send_intent WHERE tenant_id = ? AND phone_fingerprint = ? AND created_at > ?",
                      Long.class,
                      tenantId,
                      phoneFingerprint,
                      Timestamp.from(now.minus(SEND_WINDOW)));
              boolean phoneBlocked =
                  limit.blockedUntil() != null && now.isBefore(limit.blockedUntil());
              boolean overLimit = !phoneBlocked && recentCount >= 3;
              if (overLimit) {
                jdbc.update(
                    "UPDATE agendamento.client_access_phone_limit SET blocked_until = ?, updated_at = ? WHERE tenant_id = ? AND phone_fingerprint = ?",
                    Timestamp.from(now.plus(PHONE_BLOCK)),
                    Timestamp.from(now),
                    tenantId,
                    phoneFingerprint);
              }
              boolean eligible =
                  jdbc.queryForObject(
                      "SELECT EXISTS(SELECT 1 FROM agendamento.client WHERE tenant_id = ? AND normalized_phone = ?)",
                      Boolean.class,
                      tenantId,
                      phone);
              String statusValue = phoneBlocked || overLimit ? "BLOCKED" : "ISSUED";
              String delivery = "ISSUED".equals(statusValue) ? "PENDING" : "UNAVAILABLE";
              jdbc.update(
                  "UPDATE agendamento.client_access_challenge SET status = 'SUPERSEDED' WHERE tenant_id = ? AND phone_fingerprint = ? AND status = 'ISSUED'",
                  tenantId,
                  phoneFingerprint);
              jdbc.update(
                  "INSERT INTO agendamento.client_access_challenge(challenge_id, tenant_id, phone_fingerprint, fingerprint_key_id, phone_ciphertext, phone_key_id, eligible_for_access, code_salt, code_verifier, code_key_id, idempotency_digest, status, created_at, expires_at, delivery_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                  challengeId,
                  tenantId,
                  phoneFingerprint,
                  crypto.fingerprintKeyId(),
                  crypto.encryptPhone(phone),
                  crypto.phoneKeyId(),
                  eligible,
                  salt,
                  verifier,
                  crypto.keyId(),
                  intentDigest,
                  statusValue,
                  Timestamp.from(now),
                  Timestamp.from(now.plus(CHALLENGE_LIFETIME)),
                  delivery);
              if ("ISSUED".equals(statusValue)) {
                jdbc.update(
                    "INSERT INTO agendamento.client_access_send_intent(tenant_id, phone_fingerprint, fingerprint_key_id, idempotency_digest, created_at) VALUES (?, ?, ?, ?, ?)",
                    tenantId,
                    phoneFingerprint,
                    crypto.fingerprintKeyId(),
                    intentDigest,
                    Timestamp.from(now));
                jdbc.update(
                    "UPDATE agendamento.client_access_phone_limit SET send_count = ?, window_started_at = ?, updated_at = ? WHERE tenant_id = ? AND phone_fingerprint = ?",
                    Math.toIntExact(recentCount + 1),
                    Timestamp.from(now.minus(SEND_WINDOW)),
                    Timestamp.from(now),
                    tenantId,
                    phoneFingerprint);
              }
              return new RequestResult(challengeId, true, "ISSUED".equals(statusValue));
            });
    UUID resultId = requestResult.challengeId();
    if (requestResult.inserted() && requestResult.sendEligible()) {
      AccessCodeSender.DeliveryResult delivery;
      String phoneForDelivery = phone;
      try {
        delivery = sender.send(tenantId, resultId, phoneForDelivery, code);
      } catch (RuntimeException exception) {
        LOGGER.warn(
            "client_access_delivery_outcome challengeId={} tenantId={} category=transport_exception",
            resultId,
            tenantId);
        delivery = AccessCodeSender.DeliveryResult.UNKNOWN;
      }
      if (delivery != AccessCodeSender.DeliveryResult.ACCEPTED) {
        LOGGER.warn(
            "client_access_delivery_outcome challengeId={} tenantId={} category={}",
            resultId,
            tenantId,
            delivery.name().toLowerCase(java.util.Locale.ROOT));
      }
      AccessCodeSender.DeliveryResult finalDelivery = delivery;
      transaction.executeWithoutResult(
          status ->
              jdbc.update(
                  "UPDATE agendamento.client_access_challenge SET delivery_status = ? WHERE tenant_id = ? AND challenge_id = ? AND delivery_status = 'PENDING'",
                  finalDelivery.name(),
                  tenantId,
                  resultId));
    }
    return new AccessChallengeAccepted(true, (int) CHALLENGE_LIFETIME.toSeconds(), resultId);
  }

  public String verify(String slug, UUID challengeId, String code) {
    UUID tenantId = resolveTenant(slug);
    if (!crypto.configured() || !sessionConfigured()) {
      throw new AccessChallengeInvalidException();
    }
    String token = crypto.newSessionToken();
    VerificationResult result =
        transaction.execute(
            status -> {
              List<VerificationRow> rows =
                  jdbc.query(
                      "SELECT phone_ciphertext, phone_key_id, eligible_for_access, code_salt, code_verifier, code_key_id, status, expires_at, failed_attempts, blocked_until FROM agendamento.client_access_challenge WHERE tenant_id = ? AND challenge_id = ? FOR UPDATE",
                      (row, index) ->
                          new VerificationRow(
                              row.getBytes(1),
                              row.getString(2),
                              row.getBoolean(3),
                              row.getBytes(4),
                              row.getBytes(5),
                              row.getString(6),
                              row.getString(7),
                              row.getTimestamp(8).toInstant(),
                              row.getInt(9),
                              row.getTimestamp(10) == null
                                  ? null
                                  : row.getTimestamp(10).toInstant()),
                      tenantId,
                      challengeId);
              if (rows.isEmpty()) return VerificationResult.rejected();
              VerificationRow row = rows.getFirst();
              Instant now = clock.instant();
              if (!"ISSUED".equals(row.status())
                  || !now.isBefore(row.expiresAt())
                  || row.blockedUntil() != null && now.isBefore(row.blockedUntil())) {
                if ("ISSUED".equals(row.status()) && !now.isBefore(row.expiresAt())) {
                  jdbc.update(
                      "UPDATE agendamento.client_access_challenge SET status = 'EXPIRED' WHERE tenant_id = ? AND challenge_id = ?",
                      tenantId,
                      challengeId);
                }
                return VerificationResult.rejected();
              }
              byte[] supplied =
                  crypto.verifier(
                      code,
                      row.salt(),
                      challengeId.toString(),
                      tenantId.toString(),
                      row.codeKeyId());
              if (supplied == null) return VerificationResult.rejected();
              if (!crypto.matches(row.verifier(), supplied)) {
                int attempts = row.failedAttempts() + 1;
                if (attempts >= 5) {
                  jdbc.update(
                      "UPDATE agendamento.client_access_challenge SET status = 'BLOCKED', failed_attempts = 5, blocked_until = ? WHERE tenant_id = ? AND challenge_id = ?",
                      Timestamp.from(now.plus(CHALLENGE_BLOCK)),
                      tenantId,
                      challengeId);
                } else {
                  jdbc.update(
                      "UPDATE agendamento.client_access_challenge SET failed_attempts = ? WHERE tenant_id = ? AND challenge_id = ?",
                      attempts,
                      tenantId,
                      challengeId);
                }
                return VerificationResult.rejected();
              }
              if (!row.eligibleForAccess()) {
                jdbc.update(
                    "UPDATE agendamento.client_access_challenge SET status = 'VERIFIED' WHERE tenant_id = ? AND challenge_id = ? AND status = 'ISSUED'",
                    tenantId,
                    challengeId);
                return VerificationResult.rejected();
              }
              String phone = crypto.decryptPhone(row.phoneCiphertext(), row.phoneKeyId());
              jdbc.update(
                  "UPDATE agendamento.client_access_challenge SET status = 'VERIFIED' WHERE tenant_id = ? AND challenge_id = ? AND status = 'ISSUED'",
                  tenantId,
                  challengeId);
              boolean clientStillExists =
                  jdbc.queryForObject(
                      "SELECT EXISTS(SELECT 1 FROM agendamento.client WHERE tenant_id = ? AND normalized_phone = ?)",
                      Boolean.class,
                      tenantId,
                      phone);
              if (!clientStillExists) return VerificationResult.rejected();
              jdbc.update(
                  "INSERT INTO agendamento.client_access_session(session_digest, tenant_id, phone_ciphertext, phone_key_id, created_at, expires_at, challenge_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                  crypto.sessionDigest(token),
                  tenantId,
                  crypto.encryptPhone(phone),
                  crypto.phoneKeyId(),
                  Timestamp.from(now),
                  Timestamp.from(now.plusSeconds(sessionTtlSeconds)),
                  challengeId);
              return VerificationResult.accepted(token);
            });
    if (result == null || !result.accepted()) throw new AccessChallengeInvalidException();
    return result.token();
  }

  public boolean isAuthenticated(String rawCookieHeader) {
    String token = cookieValue(rawCookieHeader);
    if (token == null) return false;
    byte[] digest = crypto.digest(token);
    Instant now = clock.instant();
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM agendamento.client_access_session WHERE session_digest = ? AND revoked_at IS NULL AND expires_at > ?)",
            Boolean.class,
            digest,
            Timestamp.from(now)));
  }

  public boolean isAuthenticatedForChallenge(
      String rawCookieHeader, String slug, UUID challengeId) {
    String token = cookieValue(rawCookieHeader);
    if (token == null) return false;
    UUID tenantId;
    try {
      tenantId = resolveTenant(slug);
    } catch (AccessChallengeInvalidException exception) {
      return false;
    }
    Instant now = clock.instant();
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM agendamento.client_access_session s JOIN agendamento.client_access_challenge c ON c.tenant_id = s.tenant_id AND c.challenge_id = s.challenge_id WHERE s.session_digest = ? AND s.tenant_id = ? AND s.challenge_id = ? AND c.status = 'VERIFIED' AND s.revoked_at IS NULL AND s.expires_at > ?)",
            Boolean.class,
            crypto.sessionDigest(token),
            tenantId,
            challengeId,
            Timestamp.from(now)));
  }

  public void logout(String rawCookieHeader) {
    String token = cookieValue(rawCookieHeader);
    if (token != null)
      jdbc.update(
          "UPDATE agendamento.client_access_session SET revoked_at = ? WHERE session_digest = ? AND revoked_at IS NULL",
          Timestamp.from(clock.instant()),
          crypto.digest(token));
  }

  public boolean secureCookie() {
    return cookieSecure;
  }

  public long sessionTtlSeconds() {
    return sessionTtlSeconds;
  }

  private boolean sessionConfigured() {
    return sessionTtlSeconds >= 1 && sessionTtlSeconds <= 86_400;
  }

  private UUID resolveTenant(String slug) {
    List<UUID> values =
        jdbc.query(
            "SELECT tenant_id FROM agendamento.tenant WHERE slug = ?",
            (row, index) -> row.getObject(1, UUID.class),
            slug);
    if (values.isEmpty()) throw new AccessChallengeInvalidException();
    return values.getFirst();
  }

  private boolean fingerprintVersionCompatible() {
    return !Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS ("
                + "SELECT 1 FROM agendamento.client_access_phone_limit WHERE fingerprint_key_id <> ? "
                + "UNION ALL SELECT 1 FROM agendamento.client_access_challenge WHERE fingerprint_key_id <> ? "
                + "UNION ALL SELECT 1 FROM agendamento.client_access_request_intent WHERE fingerprint_key_id <> ? "
                + "UNION ALL SELECT 1 FROM agendamento.client_access_send_intent WHERE fingerprint_key_id <> ?)",
            Boolean.class,
            crypto.fingerprintKeyId(),
            crypto.fingerprintKeyId(),
            crypto.fingerprintKeyId(),
            crypto.fingerprintKeyId()));
  }

  private static String normalizePhone(String raw) {
    if (raw == null || !raw.matches("[+0-9 .()\\-]+")) {
      throw new InvalidAccessPhoneException();
    }
    String normalized = raw.replaceAll("[ .()\\-]", "");
    if (!normalized.matches("\\+[1-9][0-9]{7,14}")) {
      throw new InvalidAccessPhoneException();
    }
    return normalized;
  }

  private static String cookieValue(String header) {
    if (header == null) return null;
    for (String item : header.split(";")) {
      String[] pair = item.trim().split("=", 2);
      if (pair.length == 2 && "AGDCLIENT".equals(pair[0]) && pair[1].matches("[A-Za-z0-9_-]{43}"))
        return pair[1];
    }
    return null;
  }

  private record ChallengeRow(UUID challengeId, byte[] phoneFingerprint) {}

  private record RequestResult(UUID challengeId, boolean inserted, boolean sendEligible) {}

  private record VerificationResult(boolean accepted, String token) {
    private static VerificationResult accepted(String token) {
      return new VerificationResult(true, token);
    }

    private static VerificationResult rejected() {
      return new VerificationResult(false, null);
    }
  }

  private record LimitRow(Instant blockedUntil) {}

  private record VerificationRow(
      byte[] phoneCiphertext,
      String phoneKeyId,
      boolean eligibleForAccess,
      byte[] salt,
      byte[] verifier,
      String codeKeyId,
      String status,
      Instant expiresAt,
      int failedAttempts,
      Instant blockedUntil) {}
}
