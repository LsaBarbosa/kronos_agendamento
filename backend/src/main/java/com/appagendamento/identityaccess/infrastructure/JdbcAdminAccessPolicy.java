package com.appagendamento.identityaccess.infrastructure;

import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.identityaccess.application.AdminIdentityRepository;
import org.springframework.stereotype.Service;

@Service
public class JdbcAdminAccessPolicy implements AdminAccessPolicy {

  private final AdminIdentityRepository identities;

  public JdbcAdminAccessPolicy(AdminIdentityRepository identities) {
    this.identities = identities;
  }

  @Override
  public boolean isCurrent(AuthenticatedAdmin admin) {
    return identities.isActive(
        admin.issuer(), admin.subject(), admin.adminUserId(), admin.tenantId(), admin.role());
  }
}
