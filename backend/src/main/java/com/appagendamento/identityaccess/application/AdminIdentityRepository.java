package com.appagendamento.identityaccess.application;

import com.appagendamento.identityaccess.domain.AdminAccess;
import java.util.Optional;
import java.util.UUID;

public interface AdminIdentityRepository {

  Optional<AdminAccess> findActive(String issuer, String subject);

  boolean isActive(String issuer, String subject, UUID adminUserId, UUID tenantId, String role);
}
