package com.appagendamento.identityaccess.domain;

import java.io.Serializable;
import java.util.UUID;

public record AdminAccess(UUID adminUserId, UUID tenantId, String role) implements Serializable {}
