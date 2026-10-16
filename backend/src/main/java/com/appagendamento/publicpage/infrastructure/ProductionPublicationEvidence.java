package com.appagendamento.publicpage.infrastructure;

import com.appagendamento.publicpage.application.PublicationEvidence;
import com.appagendamento.publicpage.domain.PublicationBlocker;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!e2e")
public class ProductionPublicationEvidence implements PublicationEvidence {

  @Override
  public List<PublicationBlocker> unavailableEvidence(UUID tenantId) {
    return List.of(
        new PublicationBlocker(
            "TERMS_NOT_APPROVED", "Os termos e as versões aplicáveis ainda não foram aprovados."),
        new PublicationBlocker(
            "CONSENT_NOT_CAPTURED",
            "Os consentimentos aplicáveis ainda não podem ser comprovados."),
        new PublicationBlocker(
            "SUBSCRIPTION_GATE_UNAVAILABLE",
            "O gate comercial de assinatura ainda não está integrado."));
  }
}
