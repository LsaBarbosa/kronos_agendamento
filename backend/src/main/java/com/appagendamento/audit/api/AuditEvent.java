package com.appagendamento.audit.api;

import java.util.UUID;

public record AuditEvent(
    UUID eventId,
    UUID tenantId,
    UUID actorId,
    String actorType,
    String actorRef,
    String action,
    String targetType,
    UUID targetId,
    String result,
    UUID correlationId,
    UUID traceId,
    String detailsJson) {

  public AuditEvent {
    if (eventId == null
        || tenantId == null
        || actorType == null
        || actorRef == null
        || action == null
        || targetType == null
        || targetId == null
        || result == null
        || correlationId == null
        || traceId == null
        || detailsJson == null) {
      throw new IllegalArgumentException("Campos obrigatórios da auditoria ausentes");
    }
    if (!(actorType.equals("ADMIN") && actorRef.equals("TENANT_ADMIN")
            || actorType.equals("SYSTEM")
                && (actorRef.equals("CONTROLLED_TENANT_PROVISIONER")
                    || actorRef.equals("CONTROLLED_DEMO_PROVISIONER")))
        || !result.equals("COMMITTED")
        || !action.matches("TENANT_(CREATED|RENAMED|ADMIN_LINKED)")) {
      throw new IllegalArgumentException("Ator ou resultado de auditoria inválido");
    }
    if ((actorType.equals("ADMIN") && actorId == null)
        || (actorType.equals("SYSTEM") && actorId != null)) {
      throw new IllegalArgumentException("Identificador do ator incompatível com seu tipo");
    }
    boolean validDetails =
        switch (action) {
          case "TENANT_CREATED" ->
              actorType.equals("SYSTEM")
                  && detailsJson.equals("{\"changed_fields\":[\"name\",\"slug\",\"timezone\"]}");
          case "TENANT_RENAMED" ->
              actorType.equals("ADMIN")
                  && detailsJson.matches(
                      "\\{\\\"changed_fields\\\":\\[\\\"name\\\"\\],\\\"version_before\\\":[0-9]+,\\\"version_after\\\":[0-9]+}");
          case "TENANT_ADMIN_LINKED" ->
              actorRef.equals("CONTROLLED_DEMO_PROVISIONER")
                  && detailsJson.equals("{\"changed_fields\":[\"identity\",\"membership\"]}");
          default -> false;
        };
    if (!validDetails) {
      throw new IllegalArgumentException("Detalhes fora da allowlist da auditoria");
    }
  }
}
