package com.appagendamento.exports.api;

import java.time.Instant;

public record DownloadHandleResponse(String downloadHandle, Instant expiresAt) {}
