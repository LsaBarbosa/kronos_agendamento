package com.appagendamento.subscriptions.domain;

import java.time.Instant;
import java.util.UUID;

public record Subscription(
    UUID subscriptionId,
    UUID tenantId,
    String status,
    Instant trialStartedAt,
    Instant trialEndsAt,
    String planCode,
    Instant paidThroughAt,
    Instant graceEndsAt,
    long version) {}
