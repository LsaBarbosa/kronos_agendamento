package com.appagendamento.platform.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "identity.oidc.enabled", havingValue = "true")
class OidcClientConfiguration {

  @Bean
  ClientRegistrationRepository clientRegistrationRepository(
      @Value("${identity.oidc.issuer-uri}") String issuer,
      @Value("${identity.oidc.authorization-uri}") String authorizationUri,
      @Value("${identity.oidc.token-uri}") String tokenUri,
      @Value("${identity.oidc.jwk-set-uri}") String jwkSetUri,
      @Value("${identity.oidc.user-info-uri}") String userInfoUri,
      @Value("${identity.oidc.client-id}") String clientId,
      @Value("${identity.oidc.client-secret}") String clientSecret,
      @Value("${identity.oidc.redirect-uri}") String redirectUri) {
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("keycloak")
            .clientId(clientId)
            .clientSecret(clientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(redirectUri)
            .scope("openid", "profile")
            .authorizationUri(authorizationUri)
            .tokenUri(tokenUri)
            .jwkSetUri(jwkSetUri)
            .userInfoUri(userInfoUri)
            .userNameAttributeName("sub")
            .clientName("Keycloak")
            .issuerUri(issuer)
            .clientSettings(
                ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
            .build();
    return new InMemoryClientRegistrationRepository(registration);
  }
}
