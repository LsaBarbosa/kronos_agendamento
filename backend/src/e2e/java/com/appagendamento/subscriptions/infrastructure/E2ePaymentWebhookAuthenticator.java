package com.appagendamento.subscriptions.infrastructure;

import com.appagendamento.subscriptions.api.AuthenticatedPaymentEvent;
import com.appagendamento.subscriptions.api.PaymentWebhookAuthenticator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("e2e")
public class E2ePaymentWebhookAuthenticator implements PaymentWebhookAuthenticator {

  private static final Logger LOGGER = LoggerFactory.getLogger(E2ePaymentWebhookAuthenticator.class);

  private final ObjectMapper mapper;
  private final byte[] secret;

  public E2ePaymentWebhookAuthenticator(ObjectMapper mapper) {
    this.mapper = mapper;
    this.secret = System.getenv().getOrDefault("APP_BILLING_E2E_WEBHOOK_SECRET", "")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public String providerCode() {
    return "e2e";
  }

  @Override
  public boolean isConfigured() {
    return secret.length >= 32;
  }

  @Override
  public Optional<AuthenticatedPaymentEvent> authenticate(byte[] rawBody, String signatureHeader) {
    if (secret.length < 32) return Optional.empty();
    if (signatureHeader == null) {
      LOGGER.warn("payment_webhook_e2e rejected reason=signature_missing");
      return Optional.empty();
    }
    try {
      String expected = "sha256=" + HexFormat.of().formatHex(hmac(rawBody));
      if (!MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.US_ASCII),
          signatureHeader.getBytes(StandardCharsets.US_ASCII))) {
        LOGGER.warn(
            "payment_webhook_e2e rejected reason=signature_mismatch secret_bytes={} body_length={} signature_header_length={} body_sha256={}",
            secret.length,
            rawBody.length,
            signatureHeader.length(),
            HexFormat.of().formatHex(sha256(rawBody)));
        return Optional.empty();
      }
      JsonNode json = mapper.readTree(rawBody);
      AuthenticatedPaymentEvent event =
          new AuthenticatedPaymentEvent(
              "e2e",
              "synthetic-e2e-account",
              json.path("eventId").asString(),
              json.path("reference").asString(),
              json.path("sequence").asLong(),
              json.path("outcome").asString(),
              json.path("amountMinorUnits").asLong(),
              json.path("currency").asString(),
              Instant.parse(json.path("occurredAt").asString()),
              json.hasNonNull("paidThroughAt")
                  ? Instant.parse(json.path("paidThroughAt").asString())
                  : null);
      return Optional.of(event);
    } catch (RuntimeException invalid) {
      LOGGER.warn("payment_webhook_e2e rejected reason=authenticated_payload_invalid");
      return Optional.empty();
    }
  }

  private byte[] hmac(byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(body);
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException("HMAC indisponível", failure);
    }
  }

  private byte[] sha256(byte[] body) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(body);
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 indisponível", failure);
    }
  }
}
