package com.appagendamento.appointments.application;

import java.util.List;

public record ClientAppointmentPage(
    List<ClientAppointmentView> items, String nextCursor, boolean hasMore) {}
