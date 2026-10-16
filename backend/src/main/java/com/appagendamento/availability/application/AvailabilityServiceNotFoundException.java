package com.appagendamento.availability.application;

public class AvailabilityServiceNotFoundException extends RuntimeException {
  public AvailabilityServiceNotFoundException() {
    super("Serviço não encontrado");
  }
}
