package com.appagendamento.clients.application;

import com.appagendamento.clients.api.PublicClientRegistry;
import com.appagendamento.clients.domain.ClientName;
import com.appagendamento.clients.domain.E164Phone;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PublicClientRegistryService implements PublicClientRegistry {

  private final ClientRepository clients;

  public PublicClientRegistryService(ClientRepository clients) {
    this.clients = Objects.requireNonNull(clients);
  }

  @Override
  public String normalizePhone(String rawPhone) {
    return E164Phone.fromRaw(rawPhone).value();
  }

  @Override
  public String canonicalizeName(String rawName) {
    return ClientName.of(rawName).value();
  }

  @Override
  public UUID findOrCreate(UUID tenantId, String displayName, String normalizedPhone) {
    Objects.requireNonNull(tenantId);
    ClientName name = ClientName.of(displayName);
    E164Phone phone = E164Phone.fromNormalized(normalizedPhone);
    return clients.findOrCreate(tenantId, name, phone);
  }
}
