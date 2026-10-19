package com.appagendamento.notifications.application;

public class NotificationNotFoundException extends RuntimeException {
  public NotificationNotFoundException() {
    super("Notificação não encontrada");
  }
}
