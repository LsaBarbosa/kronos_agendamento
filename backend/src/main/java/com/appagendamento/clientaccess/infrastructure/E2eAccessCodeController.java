package com.appagendamento.clientaccess.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("e2e")
public class E2eAccessCodeController {

  private final E2eAccessCodeSender sender;
  private final byte[] secret;

  public E2eAccessCodeController(
      E2eAccessCodeSender sender,
      @Value("${app.client-access.e2e-emitter-secret:}") String secret) {
    this.sender = sender;
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  @GetMapping("/internal/e2e/access-challenges/{challengeId}/code")
  ResponseEntity<?> code(
      @PathVariable UUID challengeId,
      @RequestHeader(value = "X-E2E-Emitter-Secret", required = false) String supplied) {
    if (secret.length < 32
        || supplied == null
        || !MessageDigest.isEqual(secret, supplied.getBytes(StandardCharsets.UTF_8))) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .cacheControl(CacheControl.noStore())
          .build();
    }
    String code = sender.findCode(challengeId);
    return code == null
        ? ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build()
        : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
