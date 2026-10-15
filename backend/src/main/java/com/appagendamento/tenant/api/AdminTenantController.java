package com.appagendamento.tenant.api;

import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.tenant.application.GetTenant;
import com.appagendamento.tenant.domain.Tenant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminTenantController {

  private final GetTenant getTenant;

  public AdminTenantController(GetTenant getTenant) {
    this.getTenant = getTenant;
  }

  @GetMapping("/api/v1/admin/tenant")
  ResponseEntity<AdminTenantResponse> current(@AuthenticationPrincipal AuthenticatedAdmin admin) {
    Tenant tenant = getTenant.execute(admin, admin.tenantId());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new AdminTenantResponse(
                tenant.tenantId(),
                tenant.name(),
                tenant.slug().value(),
                tenant.timezone(),
                tenant.status().name()));
  }

  record AdminTenantResponse(
      UUID tenantId, String name, String slug, String timezone, String status) {}
}
