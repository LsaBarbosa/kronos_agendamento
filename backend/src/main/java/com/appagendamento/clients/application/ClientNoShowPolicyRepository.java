package com.appagendamento.clients.application;

import com.appagendamento.clients.domain.ClientNoShowPolicy;
import java.util.Optional;
import java.util.UUID;

public interface ClientNoShowPolicyRepository {

  Optional<ClientNoShowPolicy> findPolicy(UUID tenantId);

  Optional<ClientNoShowPolicy> replacePolicy(UUID tenantId, int threshold, long expectedVersion);
}
