package com.appagendamento.subscriptions.api;

import com.appagendamento.subscriptions.application.AdmitPaymentWebhook;
import com.appagendamento.subscriptions.application.InvalidPaymentWebhookException;
import com.appagendamento.subscriptions.domain.PaymentWebhookResult;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentWebhookController {

  private final AdmitPaymentWebhook admit;

  public PaymentWebhookController(AdmitPaymentWebhook admit) {
    this.admit = admit;
  }

  @PostMapping(value = "/api/v1/webhooks/payments/{providerCode}", consumes = "application/json")
  ResponseEntity<PaymentWebhookResult> receive(
      @PathVariable String providerCode,
      HttpServletRequest request,
      @RequestHeader(value = "X-Payment-Signature", required = false) String signature) {
    byte[] rawBody;
    try {
      rawBody = request.getInputStream().readNBytes(65_537);
    } catch (IOException exception) {
      throw new InvalidPaymentWebhookException();
    }
    PaymentWebhookResult result = admit.execute(providerCode, rawBody, signature);
    return ResponseEntity.status(
            result.result().equals("ADMITTED") ? HttpStatus.ACCEPTED : HttpStatus.OK)
        .cacheControl(CacheControl.noStore())
        .body(result);
  }
}
