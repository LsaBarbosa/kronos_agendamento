package com.appagendamento.identityaccess.api;

import java.util.UUID;

public interface AuthenticatedAdmin extends AuthenticatedTenant {

  UUID adminUserId();

  String issuer();

  String subject();

  String role();
}
