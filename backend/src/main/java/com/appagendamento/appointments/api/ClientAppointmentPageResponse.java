package com.appagendamento.appointments.api;

import com.appagendamento.appointments.application.ClientAppointmentPage;
import java.util.List;

public record ClientAppointmentPageResponse(
    List<ClientAppointmentResponse> items, String nextCursor, boolean hasMore) {

  static ClientAppointmentPageResponse from(ClientAppointmentPage page) {
    return new ClientAppointmentPageResponse(
        page.items().stream().map(ClientAppointmentResponse::from).toList(),
        page.nextCursor(),
        page.hasMore());
  }
}
