package com.appagendamento.identityaccess.infrastructure;

import com.appagendamento.identityaccess.api.AdminOidcUserService;
import com.appagendamento.identityaccess.application.AdminIdentityRepository;
import com.appagendamento.identityaccess.domain.AdminAccess;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.stereotype.Service;

@Service
public class DatabaseBackedOidcUserService implements AdminOidcUserService {

  private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseBackedOidcUserService.class);

  private final AdminIdentityRepository identities;
  private final OidcUserService delegate = new OidcUserService();

  public DatabaseBackedOidcUserService(AdminIdentityRepository identities) {
    this.identities = identities;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest request) {
    OidcUser user = delegate.loadUser(request);
    String issuer = user.getIssuer().toString();
    String subject = user.getSubject();
    AdminAccess access = identities.findActive(issuer, subject).orElse(null);
    if (access == null) {
      LOGGER.warn("admin_login_denied category=missing_active_database_association");
      throw new OAuth2AuthenticationException(new OAuth2Error("admin_access_denied"));
    }
    if (!"TENANT_ADMIN".equals(access.role())) {
      LOGGER.warn("admin_login_denied category=role_not_allowed");
      throw new OAuth2AuthenticationException(new OAuth2Error("admin_access_denied"));
    }
    LOGGER.info("admin_login_success");
    return new DatabaseBackedOidcUser(user, access, issuer, subject);
  }

  private static final class DatabaseBackedOidcUser
      implements OidcUser, com.appagendamento.identityaccess.api.AuthenticatedAdmin, Serializable {

    @Serial private static final long serialVersionUID = 1L;

    private final OidcUser delegate;
    private final AdminAccess access;
    private final String issuer;
    private final String subject;

    private DatabaseBackedOidcUser(
        OidcUser delegate, AdminAccess access, String issuer, String subject) {
      this.delegate = delegate;
      this.access = access;
      this.issuer = issuer;
      this.subject = subject;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
      return List.of(
          new OidcUserAuthority(getIdToken(), getUserInfo()),
          new SimpleGrantedAuthority("ROLE_" + role()));
    }

    @Override
    public Map<String, Object> getAttributes() {
      return delegate.getAttributes();
    }

    @Override
    public String getName() {
      return subject;
    }

    @Override
    public Map<String, Object> getClaims() {
      return delegate.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
      return delegate.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
      return delegate.getIdToken();
    }

    @Override
    public java.util.UUID adminUserId() {
      return access.adminUserId();
    }

    @Override
    public java.util.UUID tenantId() {
      return access.tenantId();
    }

    @Override
    public String issuer() {
      return issuer;
    }

    @Override
    public String subject() {
      return subject;
    }

    @Override
    public String role() {
      return access.role();
    }
  }
}
