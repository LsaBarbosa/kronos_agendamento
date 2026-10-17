package com.appagendamento.appointments.application;

import java.util.List;

public record AdminAppointmentHistory(String timeZone, List<AppointmentHistoryEntry> items) {}
