package com.appagendamento.idempotency.infrastructure;

import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.idempotency.application.IdempotencyKey;
import com.appagendamento.idempotency.application.IdempotencyKeyReusedException;
import com.appagendamento.idempotency.application.OperationInProgressException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PostgresIdempotencyCoordinator implements IdempotencyCoordinator {

  private final JdbcTemplate jdbc;

  public PostgresIdempotencyCoordinator(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public <T> IdempotentCommandResult<T> execute(
      UUID tenantId,
      String commandScope,
      String idempotencyKey,
      String requestDigest,
      Runnable revalidate,
      Function<UUID, T> command,
      CommandResultCodec<T> codec) {
    revalidate.run();
    String keyDigest = IdempotencyKey.digest(idempotencyKey);
    UUID operationId = UUID.randomUUID();
    List<UUID> created =
        jdbc.query(
            """
            INSERT INTO agendamento.idempotency_record
              (tenant_id, command_scope, key_digest, request_digest, operation_id, state)
            VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS')
            ON CONFLICT (tenant_id, command_scope, key_digest) DO NOTHING
            RETURNING operation_id
            """,
            (row, index) -> row.getObject("operation_id", UUID.class),
            tenantId,
            commandScope,
            keyDigest,
            requestDigest,
            operationId);
    if (created.isEmpty()) {
      IdempotencyRecord existing =
          jdbc.queryForObject(
              """
              SELECT request_digest, operation_id, state, result::text AS result
              FROM agendamento.idempotency_record
              WHERE tenant_id = ? AND command_scope = ? AND key_digest = ?
              FOR UPDATE
              """,
              (row, index) ->
                  new IdempotencyRecord(
                      row.getString("request_digest"),
                      row.getObject("operation_id", UUID.class),
                      row.getString("state"),
                      row.getString("result")),
              tenantId,
              commandScope,
              keyDigest);
      if (!existing.requestDigest().equals(requestDigest)) {
        throw new IdempotencyKeyReusedException();
      }
      return switch (existing.state()) {
        case "COMPLETED" ->
            new IdempotentCommandResult<>(
                existing.operationId(), codec.decode(existing.result()), true);
        case "IN_PROGRESS" -> throw new OperationInProgressException(existing.operationId());
        default -> throw new IllegalStateException("Estado de idempotência desconhecido");
      };
    }

    T result = command.apply(operationId);
    jdbc.update(
        """
        UPDATE agendamento.idempotency_record
        SET state = 'COMPLETED', result = ?::jsonb, completed_at = clock_timestamp()
        WHERE tenant_id = ? AND command_scope = ? AND key_digest = ?
          AND operation_id = ? AND state = 'IN_PROGRESS'
        """,
        codec.encode(result),
        tenantId,
        commandScope,
        keyDigest,
        operationId);
    return new IdempotentCommandResult<>(operationId, result, false);
  }

  private record IdempotencyRecord(
      String requestDigest, UUID operationId, String state, String result) {}
}
