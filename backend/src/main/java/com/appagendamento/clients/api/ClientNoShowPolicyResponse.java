package com.appagendamento.clients.api;

import com.appagendamento.clients.domain.ClientNoShowPolicy;

public record ClientNoShowPolicyResponse(
    boolean configured,
    String policyStatus,
    long version,
    Integer threshold,
    String restrictionIntegrationStatus) {

  public static ClientNoShowPolicyResponse absent() {
    return new ClientNoShowPolicyResponse(false, "NOT_CONFIGURED", 0, null, "TRACKED");
  }

  public static ClientNoShowPolicyResponse from(ClientNoShowPolicy policy) {
    return new ClientNoShowPolicyResponse(
        true, "CONFIGURED", policy.version(), policy.threshold(), "TRACKED");
  }
}
