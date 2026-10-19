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
                    || actorRef.equals("CONTROLLED_DEMO_PROVISIONER")
                    || actorRef.equals("PUBLIC_BOOKING"))
            || actorType.equals("CLIENT") && actorRef.equals("VERIFIED_CLIENT"))
        || !result.equals("COMMITTED")
        || !action.matches(
            "TENANT_(CREATED|RENAMED|ADMIN_LINKED)|SERVICE_(CREATED|UPDATED|ACTIVATED|DEACTIVATED)|SCHEDULE_UPDATED|AVAILABILITY_POLICY_UPDATED|SCHEDULE_EXCEPTION_(CREATED|UPDATED|REMOVED)|BLOCKED_PERIOD_(CREATED|CANCELLED)|PUBLICATION_(PUBLISHED|UNPUBLISHED)|PUBLIC_PROFILE_UPDATED|APPOINTMENT_CONFIRMED|APPOINTMENT_RESCHEDULED|APPOINTMENT_CANCELLED_BY_PROVIDER|APPOINTMENT_CANCELLED_BY_CLIENT|APPOINTMENT_COMPLETED|APPOINTMENT_NO_SHOW_RECORDED")) {
      throw new IllegalArgumentException("Ator ou resultado de auditoria inválido");
    }
    if (((actorType.equals("ADMIN") || actorType.equals("CLIENT")) && actorId == null)
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
          case "SERVICE_CREATED" ->
              actorType.equals("ADMIN")
                  && detailsJson.equals(
                      "{\"changed_fields\":[\"name\",\"description\",\"duration_minutes\",\"buffer_after_minutes\",\"status\"]}");
          case "SERVICE_UPDATED" ->
              actorType.equals("ADMIN")
                  && detailsJson.matches(
                      "\\{\\\"changed_fields\\\":\\[(?:\\\"(?:name|description|duration_minutes|buffer_after_minutes)\\\")(?:,\\\"(?:name|description|duration_minutes|buffer_after_minutes)\\\")*\\],\\\"version_before\\\":[0-9]+,\\\"version_after\\\":[0-9]+\\}");
          case "SERVICE_ACTIVATED", "SERVICE_DEACTIVATED" ->
              actorType.equals("ADMIN") && detailsJson.equals("{\"changed_fields\":[\"status\"]}");
          case "SCHEDULE_UPDATED" ->
              actorType.equals("ADMIN")
                  && detailsJson.matches(
                      "\\{\\\"changed_fields\\\":\\[\\\"weekly_periods\\\"\\],\\\"version_before\\\":[0-9]+,\\\"version_after\\\":[0-9]+\\}");
          case "AVAILABILITY_POLICY_UPDATED" ->
              actorType.equals("ADMIN")
                  && detailsJson.matches(
                      "\\{\\\"changed_fields\\\":\\[\\\"availability_policy\\\"\\],\\\"version_before\\\":[0-9]+,\\\"version_after\\\":[0-9]+\\}");
          case "SCHEDULE_EXCEPTION_CREATED",
              "SCHEDULE_EXCEPTION_UPDATED",
              "SCHEDULE_EXCEPTION_REMOVED" ->
              actorType.equals("ADMIN")
                  && detailsJson.matches(
                      "\\{\\\"changed_fields\\\":\\[\\\"schedule_exception\\\"\\],\\\"version_before\\\":[0-9]+,\\\"version_after\\\":[0-9]+\\}");
          case "BLOCKED_PERIOD_CREATED", "BLOCKED_PERIOD_CANCELLED" ->
              actorType.equals("ADMIN")
                  && detailsJson.matches(
                      "\\{\\\"changed_fields\\\":\\[\\\"blocked_period\\\"\\],\\\"version_before\\\":[0-9]+,\\\"version_after\\\":[0-9]+\\}");
          case "PUBLICATION_PUBLISHED", "PUBLICATION_UNPUBLISHED" ->
              actorType.equals("ADMIN")
                  && detailsJson.matches(
                      "\\{\\\"changed_fields\\\":\\[\\\"publication\\\"\\],\\\"version_before\\\":[0-9]+,\\\"version_after\\\":[0-9]+\\}");
          case "PUBLIC_PROFILE_UPDATED" ->
              actorType.equals("ADMIN")
                  && detailsJson.matches(
                      "\\{\\\"changed_fields\\\":\\[\\\"contact_phone\\\"\\],\\\"version_before\\\":[0-9]+,\\\"version_after\\\":[0-9]+\\}");
          case "APPOINTMENT_CONFIRMED" ->
              actorType.equals("SYSTEM")
                      && actorRef.equals("PUBLIC_BOOKING")
                      && detailsJson.equals(
                          "{\"changed_fields\":[\"appointment\"],\"version_before\":0,\"version_after\":1}")
                  || actorType.equals("ADMIN")
                      && actorRef.equals("TENANT_ADMIN")
                      && detailsJson.matches(
                          "\\{\\\"changed_fields\\\":\\[\\\"appointment\\\"\\],\\\"version_before\\\":0,\\\"version_after\\\":1,\\\"override_policies\\\":\\[(?:\\\"(?:OUTSIDE_WEEKLY_SCHEDULE|MIN_NOTICE)\\\"(?:,\\\"(?:OUTSIDE_WEEKLY_SCHEDULE|MIN_NOTICE)\\\")?)?\\],\\\"reason_provided\\\":(?:true|false),\\\"client_restriction_status\\\":\\\"(?:NOT_TRACKED|CLEAR|RESTRICTED)\\\"\\}")
                      && (detailsJson.contains("\"override_policies\":[]")
                          == detailsJson.contains("\"reason_provided\":false,"));
          case "APPOINTMENT_RESCHEDULED" ->
              actorType.equals("ADMIN")
                  && actorRef.equals("TENANT_ADMIN")
                  && validRescheduleDetails(detailsJson);
          case "APPOINTMENT_CANCELLED_BY_PROVIDER",
              "APPOINTMENT_COMPLETED",
              "APPOINTMENT_NO_SHOW_RECORDED" ->
              actorType.equals("ADMIN")
                  && actorRef.equals("TENANT_ADMIN")
                  && validOutcomeDetails(detailsJson);
          case "APPOINTMENT_CANCELLED_BY_CLIENT" ->
              actorType.equals("CLIENT")
                  && actorRef.equals("VERIFIED_CLIENT")
                  && validClientCancellationDetails(detailsJson);
          default -> false;
        };
    if (!validDetails) {
      throw new IllegalArgumentException("Detalhes fora da allowlist da auditoria");
    }
  }

  private static boolean validRescheduleDetails(String details) {
    boolean shape =
        details.matches(
            "\\{\\\"changed_fields\\\":\\[\\\"appointment\\\"\\],\\\"version_before\\\":[1-9][0-9]*,\\\"version_after\\\":[1-9][0-9]*,\\\"override_policies\\\":\\[(?:\\\"OUTSIDE_WEEKLY_SCHEDULE\\\"(?:,\\\"MIN_NOTICE\\\")?|\\\"MIN_NOTICE\\\")?\\],\\\"reason_provided\\\":(?:true|false)\\}");
    return shape
        && (details.contains("\"override_policies\":[]")
            == details.contains("\"reason_provided\":false}"));
  }

  private static boolean validOutcomeDetails(String details) {
    boolean shape =
        details.matches(
            "\\{\\\"changed_fields\\\":\\[\\\"status\\\"\\],\\\"version_before\\\":[1-9][0-9]*,\\\"version_after\\\":[1-9][0-9]*,\\\"reason_provided\\\":(?:true|false)\\}");
    if (!shape) return false;
    long before = Long.parseLong(details.replaceAll(".*\\\"version_before\\\":([0-9]+).*", "$1"));
    long after = Long.parseLong(details.replaceAll(".*\\\"version_after\\\":([0-9]+).*", "$1"));
    return after == before + 1;
  }

  private static boolean validClientCancellationDetails(String details) {
    boolean shape =
        details.matches(
            "\\{\\\"changed_fields\\\":\\[\\\"status\\\"\\],\\\"version_before\\\":[1-9][0-9]*,\\\"version_after\\\":[1-9][0-9]*\\}");
    if (!shape) return false;
    long before = Long.parseLong(details.replaceAll(".*\\\"version_before\\\":([0-9]+).*", "$1"));
    long after = Long.parseLong(details.replaceAll(".*\\\"version_after\\\":([0-9]+).*", "$1"));
    return after == before + 1;
  }
}
