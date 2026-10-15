package com.appagendamento.identityaccess.api;

import java.util.UUID;

public record AdminSessionResponse(boolean authenticated, UUID tenantId, String role) {}
