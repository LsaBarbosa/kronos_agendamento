package com.appagendamento.appointments.domain;

public class SlotNoLongerAvailableException extends RuntimeException {

  public SlotNoLongerAvailableException() {
    super("Este horário acabou de ser reservado.");
  }
}
