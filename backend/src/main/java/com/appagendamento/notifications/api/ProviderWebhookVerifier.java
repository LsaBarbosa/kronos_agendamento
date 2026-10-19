package com.appagendamento.notifications.api;

import com.appagendamento.notifications.application.NotificationRepository.VerifiedDeliveryEvidence;
import java.util.Map;
import java.util.Optional;

public interface ProviderWebhookVerifier {

  Optional<VerifiedDeliveryEvidence> verify(
      String providerCode, Map<String, String> headers, byte[] rawBody);
}
