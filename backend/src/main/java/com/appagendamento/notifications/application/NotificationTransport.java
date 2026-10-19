package com.appagendamento.notifications.application;

import com.appagendamento.notifications.domain.NotificationType;
import com.appagendamento.notifications.domain.TransportFailure;
import java.time.Instant;
import java.util.UUID;

public interface NotificationTransport {

  TransportResult send(NotificationMessage message);

  record NotificationMessage(
      UUID notificationId,
      UUID tenantId,
      UUID appointmentId,
      long appointmentVersion,
      NotificationType type,
      String e164Recipient,
      String clientName,
      String serviceName,
      Instant startsAt,
      Instant serviceEndsAt,
      String timeZone,
      String externalIdempotencyKey) {}

  record TransportResult(
      boolean accepted, String providerCode, String providerReference, TransportFailure failure) {
    public TransportResult {
      if (accepted && (failure != null || providerCode == null || providerReference == null)) {
        throw new IllegalArgumentException("Aceite do transporte requer referência do provedor");
      }
      if (!accepted && (failure == null || providerCode != null || providerReference != null)) {
        throw new IllegalArgumentException(
            "Falha do transporte deve ter somente classificação segura");
      }
    }

    public static TransportResult accepted(String providerCode, String providerReference) {
      return new TransportResult(true, providerCode, providerReference, null);
    }

    public static TransportResult failed(TransportFailure failure) {
      return new TransportResult(false, null, null, failure);
    }
  }
}
