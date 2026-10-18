package com.appagendamento.clientaccess.api;

import java.util.UUID;

public record AccessChallengeAccepted(
    boolean challengeAccepted, int expiresInSeconds, UUID challengeId) {}
