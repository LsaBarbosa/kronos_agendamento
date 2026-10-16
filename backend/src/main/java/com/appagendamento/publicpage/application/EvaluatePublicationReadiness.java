package com.appagendamento.publicpage.application;

import com.appagendamento.publicpage.domain.PublicBusiness;
import com.appagendamento.publicpage.domain.PublicationBlocker;
import com.appagendamento.publicpage.domain.PublicationState;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EvaluatePublicationReadiness {

  private final PublicationRepository publications;
  private final PublicationEvidence evidence;

  public EvaluatePublicationReadiness(
      PublicationRepository publications, PublicationEvidence evidence) {
    this.publications = publications;
    this.evidence = evidence;
  }

  public PublicationReadiness execute(UUID tenantId) {
    PublicBusiness business = publications.findBusinessByTenantId(tenantId).orElse(null);
    PublicationState state = publications.findState(tenantId).orElse(null);
    List<PublicationBlocker> blockers = new ArrayList<>();
    if (business == null || state == null) {
      blockers.add(
          new PublicationBlocker("TENANT_PROFILE_UNAVAILABLE", "Perfil do negócio indisponível."));
      return new PublicationReadiness(false, blockers);
    }
    if (!"ACTIVE".equals(business.status())) {
      blockers.add(new PublicationBlocker("TENANT_NOT_ACTIVE", "O negócio precisa estar ativo."));
    }
    if (business.name() == null || business.name().isBlank()) {
      blockers.add(new PublicationBlocker("BUSINESS_NAME_REQUIRED", "Informe o nome do negócio."));
    }
    if (business.slug() == null || business.slug().isBlank()) {
      blockers.add(new PublicationBlocker("PUBLIC_SLUG_REQUIRED", "Configure o endereço público."));
    }
    if (business.timeZone() == null
        || !ZoneId.getAvailableZoneIds().contains(business.timeZone())) {
      blockers.add(new PublicationBlocker("TIME_ZONE_REQUIRED", "Configure um fuso horário IANA."));
    }
    if (state.contactPhone() == null || state.contactPhone().isBlank()) {
      blockers.add(
          new PublicationBlocker("CONTACT_PHONE_REQUIRED", "Informe um telefone de contato."));
    }
    if (!publications.hasActiveService(tenantId)) {
      blockers.add(
          new PublicationBlocker("ACTIVE_SERVICE_REQUIRED", "Cadastre ao menos um serviço ativo."));
    }
    if (!publications.hasWeeklyPeriod(tenantId)) {
      blockers.add(
          new PublicationBlocker(
              "WEEKLY_PERIOD_REQUIRED", "Configure ao menos um período semanal."));
    }
    if (!publications.hasAvailabilityPolicy(tenantId)) {
      blockers.add(
          new PublicationBlocker(
              "AVAILABILITY_POLICY_REQUIRED", "Configure as políticas de disponibilidade."));
    }
    blockers.addAll(evidence.unavailableEvidence(tenantId));
    return new PublicationReadiness(blockers.isEmpty(), blockers);
  }
}
