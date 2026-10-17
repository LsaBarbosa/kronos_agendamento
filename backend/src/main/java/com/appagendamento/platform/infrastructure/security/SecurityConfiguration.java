package com.appagendamento.platform.infrastructure.security;

import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AdminOidcUserService;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

  private static final Logger LOGGER = LoggerFactory.getLogger(SecurityConfiguration.class);
  private static final String ADMIN_API = "/api/v1/admin/**";

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      SecurityErrorWriter errors,
      ObjectProvider<AdminOidcUserService> adminOidcUserService,
      ObjectProvider<AdminAccessPolicy> adminAccessPolicy,
      @Value("${identity.oidc.enabled:false}") boolean oidcEnabled,
      @Value("${identity.oidc.frontend-url:http://localhost:5173/admin}") String frontendUrl)
      throws Exception {
    http.sessionManagement(
            session ->
                session
                    .sessionCreationPolicy(
                        oidcEnabled
                            ? SessionCreationPolicy.IF_REQUIRED
                            : SessionCreationPolicy.STATELESS)
                    .sessionFixation(fixation -> fixation.changeSessionId()))
        .csrf(
            csrf ->
                csrf.ignoringRequestMatchers(
                    SecurityConfiguration::isAnonymousPublicTelemetryRequest))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(
            access ->
                access
                    .requestMatchers(
                        HttpMethod.GET,
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/csrf")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/public/*",
                        "/api/v1/public/*/services",
                        "/api/v1/public/*/services/*",
                        "/api/v1/public/*/availability")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/public/*/booking-intents/current")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/public/*/telemetry")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/public/*/appointments")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/public/*/booking-intents/current")
                    .permitAll()
                    .requestMatchers("/oauth2/**", "/login/**")
                    .permitAll()
                    .requestMatchers(ADMIN_API)
                    .access(
                        (authentication, context) -> {
                          Authentication current = authentication.get();
                          if (!oidcEnabled
                              || current == null
                              || !(current.getPrincipal() instanceof AuthenticatedAdmin admin)
                              || !current.getAuthorities().stream()
                                  .anyMatch(
                                      authority ->
                                          "ROLE_TENANT_ADMIN".equals(authority.getAuthority()))) {
                            return new AuthorizationDecision(false);
                          }
                          boolean currentAdmin = adminAccessPolicy.getObject().isCurrent(admin);
                          if (!currentAdmin) {
                            LOGGER.warn(
                                "admin_authorization_denied category=inactive_database_access");
                            var session = context.getRequest().getSession(false);
                            if (session != null) {
                              try {
                                session.invalidate();
                              } catch (IllegalStateException ignored) {
                              }
                            }
                          }
                          return new AuthorizationDecision(currentAdmin);
                        })
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/v1/admin/logout")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("AGDSESSION", "JSESSIONID")
                    .logoutSuccessHandler(
                        (request, response, authentication) -> response.sendRedirect(frontendUrl)));

    if (oidcEnabled) {
      AdminOidcUserService userService = adminOidcUserService.getObject();
      http.oauth2Login(
          login ->
              login
                  .userInfoEndpoint(userInfo -> userInfo.oidcUserService(userService))
                  .defaultSuccessUrl(frontendUrl, true)
                  .failureHandler(authenticationFailureHandler()));
      http.oauth2Client(
          client -> client.authorizedClientRepository(new TransientAuthorizedClientRepository()));
    } else {
      http.logout(AbstractHttpConfigurer::disable);
    }
    return http.build();
  }

  private static boolean isAnonymousPublicTelemetryRequest(
      jakarta.servlet.http.HttpServletRequest request) {
    if (!"POST".equals(request.getMethod())) {
      return false;
    }
    String servletPath = request.getServletPath();
    String requestPath = request.getRequestURI().substring(request.getContextPath().length());
    return servletPath.matches("/api/v1/public/[^/]+/telemetry")
        || requestPath.matches("/api/v1/public/[^/]+/telemetry");
  }

  private static AuthenticationFailureHandler authenticationFailureHandler() {
    return (request, response, exception) -> {
      LOGGER.warn("admin_login_denied category=oidc_authentication_failure");
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      response.setHeader("Cache-Control", "no-store");
      response.getWriter().write("{\"error\":\"ADMIN_ACCESS_DENIED\"}");
    };
  }

  private static final class TransientAuthorizedClientRepository
      implements OAuth2AuthorizedClientRepository {

    @Override
    public <T extends org.springframework.security.oauth2.client.OAuth2AuthorizedClient>
        T loadAuthorizedClient(
            String clientRegistrationId,
            Authentication principal,
            jakarta.servlet.http.HttpServletRequest request) {
      return null;
    }

    @Override
    public void saveAuthorizedClient(
        org.springframework.security.oauth2.client.OAuth2AuthorizedClient authorizedClient,
        Authentication principal,
        jakarta.servlet.http.HttpServletRequest request,
        jakarta.servlet.http.HttpServletResponse response) {}

    @Override
    public void removeAuthorizedClient(
        String clientRegistrationId,
        Authentication principal,
        jakarta.servlet.http.HttpServletRequest request,
        jakarta.servlet.http.HttpServletResponse response) {}
  }
}
