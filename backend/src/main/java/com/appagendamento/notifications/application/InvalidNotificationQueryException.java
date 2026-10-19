package com.appagendamento.notifications.application;

public class InvalidNotificationQueryException extends RuntimeException {
  public InvalidNotificationQueryException() {
    super("Parâmetros de consulta inválidos");
  }
}
