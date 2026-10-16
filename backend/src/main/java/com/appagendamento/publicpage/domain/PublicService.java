package com.appagendamento.publicpage.domain;

import java.util.UUID;

public record PublicService(UUID serviceId, String name, String description, int durationMinutes) {}
