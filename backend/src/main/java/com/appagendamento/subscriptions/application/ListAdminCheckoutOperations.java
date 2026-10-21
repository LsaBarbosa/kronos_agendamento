package com.appagendamento.subscriptions.application;

import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.subscriptions.domain.CheckoutOperation;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ListAdminCheckoutOperations {

  private final AdminAccessPolicy access;
  private final BillingRepository billing;

  public ListAdminCheckoutOperations(AdminAccessPolicy access, BillingRepository billing) {
    this.access = access;
    this.billing = billing;
  }

  public List<CheckoutOperation> execute(AuthenticatedAdmin admin) {
    if (admin == null || !"TENANT_ADMIN".equals(admin.role()) || !access.isCurrent(admin)) {
      throw new SubscriptionAccessDeniedException();
    }
    return billing.findOpenCheckouts(admin.tenantId());
  }
}
