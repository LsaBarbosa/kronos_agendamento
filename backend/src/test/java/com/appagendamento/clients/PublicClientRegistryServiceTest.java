package com.appagendamento.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.appagendamento.clients.application.ClientRepository;
import com.appagendamento.clients.application.PublicClientRegistryService;
import com.appagendamento.clients.domain.ClientName;
import com.appagendamento.clients.domain.E164Phone;
import com.appagendamento.clients.domain.InvalidClientNameException;
import com.appagendamento.clients.domain.InvalidE164PhoneException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicClientRegistryServiceTest {

  private final ClientRepository clients = mock(ClientRepository.class);
  private final PublicClientRegistryService registry = new PublicClientRegistryService(clients);

  @Test
  void exposesCanonicalPhoneAndDelegatesFindOrCreateWithoutChangingExistingName() {
    UUID tenantId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    when(clients.findOrCreate(
            tenantId, ClientName.of("Ana"), E164Phone.fromNormalized("+5511999999999")))
        .thenReturn(clientId);

    assertThat(registry.normalizePhone("+55 (11) 99999-9999")).isEqualTo("+5511999999999");
    assertThat(registry.canonicalizeName("  Jose\u0301  ")).isEqualTo("José");
    assertThat(registry.findOrCreate(tenantId, " Ana ", "+5511999999999")).isEqualTo(clientId);
    verify(clients)
        .findOrCreate(tenantId, ClientName.of("Ana"), E164Phone.fromNormalized("+5511999999999"));
  }

  @Test
  void rejectsInvalidInputBeforeCallingRepository() {
    UUID tenantId = UUID.randomUUID();

    assertThatThrownBy(() -> registry.findOrCreate(tenantId, " ", "+5511999999999"))
        .isInstanceOf(InvalidClientNameException.class);
    assertThatThrownBy(() -> registry.findOrCreate(tenantId, "Ana", "5511999999999"))
        .isInstanceOf(InvalidE164PhoneException.class);
    verifyNoInteractions(clients);
  }
}
