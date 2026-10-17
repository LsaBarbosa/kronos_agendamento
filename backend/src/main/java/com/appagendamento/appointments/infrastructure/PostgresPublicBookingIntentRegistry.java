package com.appagendamento.appointments.infrastructure;

import com.appagendamento.appointments.application.PublicAppointmentReceipt;
import com.appagendamento.appointments.application.PublicAppointmentReceiptCodec;
import com.appagendamento.appointments.application.PublicBookingIntent;
import com.appagendamento.appointments.application.PublicBookingIntentRegistry;
import com.appagendamento.idempotency.application.IdempotencyKeyReusedException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresPublicBookingIntentRegistry implements PublicBookingIntentRegistry {

  private final JdbcTemplate jdbc;
  private final PublicAppointmentReceiptCodec receiptCodec;

  public PostgresPublicBookingIntentRegistry(
      JdbcTemplate jdbc, PublicAppointmentReceiptCodec receiptCodec) {
    this.jdbc = jdbc;
    this.receiptCodec = receiptCodec;
  }

  @Override
  public PublicBookingIntent bind(PublicBookingIntent intent) {
    List<PublicBookingIntent> inserted =
        jdbc.query(
            """
            INSERT INTO agendamento.public_booking_intent
              (tenant_id, command_scope, key_digest, recovery_digest, request_digest, expires_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            RETURNING tenant_id, command_scope, key_digest, recovery_digest,
                      request_digest, expires_at
            """,
            (row, index) ->
                map(
                    row.getObject(1, UUID.class),
                    row.getString(2),
                    row.getString(3),
                    row.getString(4),
                    row.getString(5),
                    row.getObject(6, OffsetDateTime.class)),
            intent.tenantId(),
            intent.commandScope(),
            intent.keyDigest(),
            intent.recoveryDigest(),
            intent.requestDigest(),
            OffsetDateTime.ofInstant(intent.expiresAt(), ZoneOffset.UTC));
    if (!inserted.isEmpty()) {
      return inserted.getFirst();
    }
    return findByKey(intent.tenantId(), intent.commandScope(), intent.keyDigest())
        .orElseThrow(IdempotencyKeyReusedException::new);
  }

  @Override
  public Optional<PublicBookingIntent> findByKey(
      UUID tenantId, String commandScope, String keyDigest) {
    return jdbc
        .query(
            """
        SELECT tenant_id, command_scope, key_digest, recovery_digest, request_digest, expires_at
        FROM agendamento.public_booking_intent
        WHERE tenant_id = ? AND command_scope = ? AND key_digest = ?
        """,
            (row, index) ->
                map(
                    row.getObject("tenant_id", UUID.class),
                    row.getString("command_scope"),
                    row.getString("key_digest"),
                    row.getString("recovery_digest"),
                    row.getString("request_digest"),
                    row.getObject("expires_at", OffsetDateTime.class)),
            tenantId,
            commandScope,
            keyDigest)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<PublicBookingIntent> findBySlugAndRecoveryDigest(
      String slug, String recoveryDigest) {
    return jdbc
        .query(
            """
        SELECT i.tenant_id, i.command_scope, i.key_digest, i.recovery_digest,
               i.request_digest, i.expires_at
        FROM agendamento.public_booking_intent i
        JOIN agendamento.tenant t ON t.tenant_id = i.tenant_id
        WHERE t.slug = ? AND i.recovery_digest = ? AND i.expires_at > clock_timestamp()
        """,
            (row, index) ->
                map(
                    row.getObject("tenant_id", UUID.class),
                    row.getString("command_scope"),
                    row.getString("key_digest"),
                    row.getString("recovery_digest"),
                    row.getString("request_digest"),
                    row.getObject("expires_at", OffsetDateTime.class)),
            slug,
            recoveryDigest)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<PublicAppointmentReceipt> findCompletedReceipt(PublicBookingIntent intent) {
    return jdbc
        .query(
            """
        SELECT result::text
        FROM agendamento.idempotency_record
        WHERE tenant_id = ? AND command_scope = ? AND key_digest = ? AND state = 'COMPLETED'
        """,
            (row, index) -> receiptCodec.decode(row.getString(1)),
            intent.tenantId(),
            intent.commandScope(),
            intent.keyDigest())
        .stream()
        .findFirst();
  }

  private static PublicBookingIntent map(
      UUID tenantId,
      String commandScope,
      String keyDigest,
      String recoveryDigest,
      String requestDigest,
      OffsetDateTime expiresAt) {
    return new PublicBookingIntent(
        tenantId, commandScope, keyDigest, recoveryDigest, requestDigest, expiresAt.toInstant());
  }
}
