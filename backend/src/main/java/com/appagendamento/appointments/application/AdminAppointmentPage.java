package com.appagendamento.appointments.application;

import java.util.List;

public record AdminAppointmentPage(
    String timeZone, List<AdminAppointmentView> items, String nextCursor, boolean hasMore) {}
