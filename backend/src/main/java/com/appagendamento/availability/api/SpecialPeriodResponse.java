package com.appagendamento.availability.api;

public record SpecialPeriodResponse(
    String localStart, String localEnd, String localStartOffset, String localEndOffset) {}
