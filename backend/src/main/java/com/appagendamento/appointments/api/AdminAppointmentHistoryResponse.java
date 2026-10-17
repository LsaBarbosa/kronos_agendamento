package com.appagendamento.appointments.api;

import com.appagendamento.appointments.application.AdminAppointmentHistory;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

public record AdminAppointmentHistoryResponse(String timeZone, List<Entry> items) {

  public static AdminAppointmentHistoryResponse from(AdminAppointmentHistory history) {
    ZoneId zone = ZoneId.of(history.timeZone());
    return new AdminAppointmentHistoryResponse(
        history.timeZone(),
        history.items().stream()
            .map(
                item ->
                    new Entry(
                        item.fromStatus(),
                        item.toStatus(),
                        item.actorType(),
                        item.actorRef(),
                        item.reasonCode(),
                        item.reason(),
                        item.occurredAt().atZone(zone).toOffsetDateTime(),
                        item.previousStartsAt() == null
                            ? null
                            : item.previousStartsAt().atZone(zone).toOffsetDateTime(),
                        item.previousOccupiedUntil() == null
                            ? null
                            : item.previousOccupiedUntil().atZone(zone).toOffsetDateTime(),
                        item.newStartsAt() == null
                            ? null
                            : item.newStartsAt().atZone(zone).toOffsetDateTime(),
                        item.newOccupiedUntil() == null
                            ? null
                            : item.newOccupiedUntil().atZone(zone).toOffsetDateTime(),
                        item.versionBefore(),
                        item.versionAfter()))
            .toList());
  }

  public record Entry(
      String fromStatus,
      String toStatus,
      String actorType,
      String actorRef,
      String reasonCode,
      String reason,
      OffsetDateTime occurredAt,
      OffsetDateTime previousStartsAt,
      OffsetDateTime previousOccupiedUntil,
      OffsetDateTime newStartsAt,
      OffsetDateTime newOccupiedUntil,
      Long versionBefore,
      Long versionAfter) {}
}
