package com.appagendamento.clientaccess.api;

import java.util.Optional;

public interface ClientAccessIdentityQuery {

  Optional<VerifiedClientIdentity> currentIdentity(String rawCookieHeader);
}
