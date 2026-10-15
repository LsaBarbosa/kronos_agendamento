package com.appagendamento.identityaccess.api;

public interface AdminAccessPolicy {

  boolean isCurrent(AuthenticatedAdmin admin);
}
