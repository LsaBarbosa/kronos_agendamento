package com.appagendamento.subscriptions.domain;

import java.time.Instant;
import java.util.UUID;

public record AnnualRenewalNoticeView(
    UUID noticeId,
    long subscriptionVersion,
    Instant paidThroughAt,
    Instant scheduledAt,
    String state,
    String obsoleteReason) {}
