package com.appagendamento.identityaccess.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminSessionController {

  @GetMapping("/api/v1/csrf")
  ResponseEntity<CsrfTokenResponse> csrf(CsrfToken csrfToken) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new CsrfTokenResponse(csrfToken.getHeaderName(), csrfToken.getToken()));
  }

  @GetMapping("/api/v1/admin/session")
  ResponseEntity<AdminSessionResponse> current(@AuthenticationPrincipal AuthenticatedAdmin admin) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new AdminSessionResponse(true, admin.tenantId(), admin.role()));
  }
}
