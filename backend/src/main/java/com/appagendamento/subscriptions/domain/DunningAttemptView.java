package com.appagendamento.subscriptions.domain;

import java.time.Instant;

public record DunningAttemptView(int logicalSequence, Instant scheduledAt, String state) {}
