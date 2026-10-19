package com.appagendamento.notifications.application;

public class NotificationAdminAccessDeniedException extends RuntimeException {
  public NotificationAdminAccessDeniedException() {
    super("Acesso administrativo não permitido");
  }
}
