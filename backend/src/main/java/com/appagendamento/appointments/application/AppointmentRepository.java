package com.appagendamento.appointments.application;

import com.appagendamento.appointments.domain.Appointment;

public interface AppointmentRepository {

  void insertConfirmed(Appointment appointment, String operationId, String traceId);
}
