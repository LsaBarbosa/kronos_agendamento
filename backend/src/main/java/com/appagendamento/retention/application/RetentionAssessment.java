package com.appagendamento.retention.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RetentionAssessment(
    Instant referenceAt,
    Instant startedAt,
    Instant completedAt,
    int countLimit,
    int sourceQueryTimeoutSeconds,
    String readConsistency,
    boolean informationalOnly,
    String tenantLifecycleStatus,
    PolicyStatus policy,
    List<Category> categories,
    List<String> blockers) {

  public record PolicyStatus(
      String initialPostCancellationOrSuspensionRetentionStatus,
      int initialPostCancellationOrSuspensionMonths,
      String startMarker,
      String categoryPolicies,
      String legalBases,
      String holdAuthority,
      String holdInventory) {}

  public record Count(Integer value, String status, int limit) {}

  public record Category(
      String code,
      String evaluationStatus,
      String sourceStatus,
      Instant observedAt,
      Map<String, Count> counts,
      Map<String, String> facts,
      List<String> reasons) {}
}
