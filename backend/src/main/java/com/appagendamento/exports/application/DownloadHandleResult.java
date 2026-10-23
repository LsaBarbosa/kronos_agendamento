package com.appagendamento.exports.application;

import java.time.Instant;

public record DownloadHandleResult(String downloadHandle, Instant expiresAt) {}
