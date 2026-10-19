package com.appagendamento.clients.api;

import com.appagendamento.clients.application.GetClientNoShowPolicy;
import com.appagendamento.clients.application.ReplaceClientNoShowPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminClientNoShowPolicyController {

  private final GetClientNoShowPolicy getPolicy;
  private final ReplaceClientNoShowPolicy replacePolicy;

  public AdminClientNoShowPolicyController(
      GetClientNoShowPolicy getPolicy, ReplaceClientNoShowPolicy replacePolicy) {
    this.getPolicy = getPolicy;
    this.replacePolicy = replacePolicy;
  }

  @GetMapping("/api/v1/admin/no-show-policy")
  ResponseEntity<ClientNoShowPolicyResponse> policy(
      @AuthenticationPrincipal AuthenticatedAdmin actor) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            getPolicy
                .execute(actor)
                .map(ClientNoShowPolicyResponse::from)
                .orElseGet(ClientNoShowPolicyResponse::absent));
  }

  @PutMapping("/api/v1/admin/no-show-policy")
  ResponseEntity<ClientNoShowPolicyResponse> replace(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody ClientNoShowPolicyRequest request) {
    var result =
        replacePolicy.execute(
            actor, idempotencyKey, request.expectedVersion(), request.threshold());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .header("X-Operation-Id", result.operationId().toString())
        .body(ClientNoShowPolicyResponse.from(result.value()));
  }
}
