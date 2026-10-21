package com.appagendamento.subscriptions.domain;

import java.time.Instant;
import java.util.UUID;

public record TrialGrantReceipt(
    boolean granted, UUID subscriptionId, Instant trialStartedAt, Instant trialEndsAt) {}
