package com.appagendamento.clients.application;

import com.appagendamento.clients.domain.ClientNoShowPolicy;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class GetClientNoShowPolicy {

  private final ClientNoShowPolicyRepository policies;
  private final AdminAccessPolicy accessPolicy;

  public GetClientNoShowPolicy(
      ClientNoShowPolicyRepository policies, AdminAccessPolicy accessPolicy) {
    this.policies = policies;
    this.accessPolicy = accessPolicy;
  }

  public Optional<ClientNoShowPolicy> execute(AuthenticatedAdmin actor) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new ClientNoShowPolicyAccessDeniedException();
    }
    return policies.findPolicy(actor.tenantId());
  }
}
