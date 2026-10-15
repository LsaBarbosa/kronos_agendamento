package com.appagendamento.outbox.api;

import java.util.UUID;

public record OutboxEvent(
    UUID eventId,
    UUID tenantId,
    String aggregateType,
    UUID aggregateId,
    long aggregateVersion,
    String eventType,
    int schemaVersion,
    UUID operationId,
    UUID correlationId,
    String payloadJson) {

  public OutboxEvent {
    if (eventId == null
        || tenantId == null
        || aggregateType == null
        || aggregateId == null
        || eventType == null
        || operationId == null
        || correlationId == null
        || payloadJson == null
        || aggregateVersion < 0
        || schemaVersion < 1) {
      throw new IllegalArgumentException("Campos obrigatórios do evento outbox ausentes");
    }
    if (!aggregateType.equals("TENANT") || !eventType.equals("TENANT_RENAMED")) {
      throw new IllegalArgumentException("Evento fora da allowlist desta integração");
    }
    if (!payloadJson.matches("\\{\"tenantId\":\"[0-9a-fA-F-]{36}\",\"version\":[0-9]+\\}")) {
      throw new IllegalArgumentException("Payload fora da allowlist do evento");
    }
  }
}
