package com.appagendamento.publicpage.application;

import com.appagendamento.publicpage.domain.PublicBusiness;
import com.appagendamento.publicpage.domain.PublicService;
import com.appagendamento.publicpage.domain.PublicationState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PublicationRepository {

  Optional<PublicBusiness> findBusinessByTenantId(UUID tenantId);

  Optional<PublicBusiness> findBusinessBySlug(String slug);

  Optional<PublicationState> findState(UUID tenantId);

  boolean updatePublished(UUID tenantId, boolean published, long expectedVersion);

  boolean updateContactPhone(UUID tenantId, String contactPhone, long expectedVersion);

  List<PublicService> findActiveServices(UUID tenantId);

  Optional<PublicService> findActiveService(UUID tenantId, UUID serviceId);

  boolean hasActiveService(UUID tenantId);

  boolean hasWeeklyPeriod(UUID tenantId);

  boolean hasAvailabilityPolicy(UUID tenantId);
}
