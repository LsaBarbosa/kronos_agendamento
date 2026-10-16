package com.appagendamento.publicpage.domain;

import java.util.UUID;

public record PublicBusiness(
    UUID tenantId,
    String name,
    String slug,
    String timeZone,
    String status,
    String contactPhone,
    boolean published,
    long publicationVersion) {}
