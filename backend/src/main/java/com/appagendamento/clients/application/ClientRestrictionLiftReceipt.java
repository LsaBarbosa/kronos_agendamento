package com.appagendamento.clients.application;

import java.util.UUID;

public record ClientRestrictionLiftReceipt(UUID restrictionId, String status, long version) {}
