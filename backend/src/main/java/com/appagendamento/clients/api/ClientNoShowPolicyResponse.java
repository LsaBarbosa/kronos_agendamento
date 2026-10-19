package com.appagendamento.clients.api;

import com.appagendamento.clients.domain.ClientNoShowPolicy;

public record ClientNoShowPolicyResponse(
    boolean configured, long version, Integer threshold, String restrictionStatus) {

  public static ClientNoShowPolicyResponse absent() {
    return new ClientNoShowPolicyResponse(false, 0, null, "NOT_TRACKED");
  }

  public static ClientNoShowPolicyResponse from(ClientNoShowPolicy policy) {
    return new ClientNoShowPolicyResponse(
        true, policy.version(), policy.threshold(), "NOT_TRACKED");
  }
}
