package com.appagendamento.clientaccess.api;

import com.appagendamento.clientaccess.application.ClientAccessService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClientAccessController {

  private final ClientAccessService service;

  public ClientAccessController(ClientAccessService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/public/{slug}/access-challenges")
  ResponseEntity<AccessChallengeAccepted> request(
      @PathVariable String slug,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
      @Valid @RequestBody AccessChallengeRequest request) {
    return ResponseEntity.accepted()
        .cacheControl(CacheControl.noStore())
        .body(service.requestChallenge(slug, request.phone(), idempotencyKey));
  }

  @PostMapping("/api/v1/public/{slug}/access-challenges/{challengeId}/verify")
  ResponseEntity<Void> verify(
      @PathVariable String slug,
      @PathVariable UUID challengeId,
      @Valid @RequestBody VerifyAccessChallengeRequest request,
      HttpServletResponse response) {
    String token = service.verify(slug, challengeId, request.code());
    ResponseCookie cookie =
        ResponseCookie.from("AGDCLIENT", token)
            .httpOnly(true)
            .secure(service.secureCookie())
            .sameSite("Lax")
            .path("/api/v1/client")
            .maxAge(service.sessionTtlSeconds())
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  @GetMapping("/api/v1/client/session")
  ResponseEntity<ClientAccessSessionView> session(
      @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new ClientAccessSessionView(service.isAuthenticated(cookie)));
  }

  @PostMapping("/api/v1/client/session/logout")
  ResponseEntity<Void> logout(
      @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
      HttpServletResponse response) {
    service.logout(cookie);
    ResponseCookie expired =
        ResponseCookie.from("AGDCLIENT", "")
            .httpOnly(true)
            .secure(service.secureCookie())
            .sameSite("Lax")
            .path("/api/v1/client")
            .maxAge(0)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }
}
