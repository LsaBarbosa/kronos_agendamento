package com.appagendamento.appointments.api;

import com.appagendamento.appointments.application.AdminAppointmentPage;
import java.util.List;

public record AdminAppointmentPageResponse(
    String timeZone, List<AdminAppointmentResponse> items, String nextCursor, boolean hasMore) {

  public static AdminAppointmentPageResponse from(AdminAppointmentPage page) {
    return new AdminAppointmentPageResponse(
        page.timeZone(),
        page.items().stream().map(AdminAppointmentResponse::from).toList(),
        page.nextCursor(),
        page.hasMore());
  }
}
