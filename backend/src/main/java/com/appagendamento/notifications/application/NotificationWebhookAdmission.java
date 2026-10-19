package com.appagendamento.notifications.application;

import com.appagendamento.notifications.api.ProviderWebhookVerifier;
import com.appagendamento.notifications.application.NotificationRepository.VerifiedDeliveryEvidence;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class NotificationWebhookAdmission {

  private static final int MAX_BODY_BYTES = 65_536;
  private final ObjectProvider<ProviderWebhookVerifier> verifiers;
  private final NotificationRepository notifications;

  public NotificationWebhookAdmission(
      ObjectProvider<ProviderWebhookVerifier> verifiers, NotificationRepository notifications) {
    this.verifiers = verifiers;
    this.notifications = notifications;
  }

  public AdmissionResult admit(String providerCode, Map<String, String> headers, byte[] rawBody) {
    if (rawBody == null || rawBody.length == 0 || rawBody.length > MAX_BODY_BYTES) {
      return AdmissionResult.REJECTED;
    }
    ProviderWebhookVerifier verifier = verifiers.getIfAvailable();
    if (verifier == null) {
      return AdmissionResult.CLOSED;
    }
    VerifiedDeliveryEvidence evidence =
        verifier.verify(providerCode, Map.copyOf(headers), rawBody).orElse(null);
    if (evidence == null) {
      return AdmissionResult.REJECTED;
    }
    return notifications.recordEvidence(evidence)
        ? AdmissionResult.ADMITTED
        : AdmissionResult.DUPLICATE_OR_UNMATCHED;
  }

  public enum AdmissionResult {
    ADMITTED,
    DUPLICATE_OR_UNMATCHED,
    REJECTED,
    CLOSED
  }
}
