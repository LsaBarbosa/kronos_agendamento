package com.appagendamento.retention.api;

import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.retention.application.GetRetentionAssessment;
import com.appagendamento.retention.application.RetentionAssessment;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminRetentionAssessmentController {

  private final GetRetentionAssessment getAssessment;

  public AdminRetentionAssessmentController(GetRetentionAssessment getAssessment) {
    this.getAssessment = getAssessment;
  }

  @GetMapping("/api/v1/admin/retention/assessment")
  ResponseEntity<AssessmentResponse> assess(@AuthenticationPrincipal AuthenticatedAdmin actor) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(AssessmentResponse.from(getAssessment.execute(actor)));
  }

  public record AssessmentResponse(
      java.time.Instant referenceAt,
      java.time.Instant startedAt,
      java.time.Instant completedAt,
      String readConsistency,
      boolean informationalOnly,
      String tenantLifecycleStatus,
      int countLimit,
      int sourceQueryTimeoutSeconds,
      PolicyResponse policy,
      List<CategoryResponse> categories,
      List<String> blockers) {

    static AssessmentResponse from(RetentionAssessment assessment) {
      return new AssessmentResponse(
          assessment.referenceAt(),
          assessment.startedAt(),
          assessment.completedAt(),
          assessment.readConsistency(),
          assessment.informationalOnly(),
          assessment.tenantLifecycleStatus(),
          assessment.countLimit(),
          assessment.sourceQueryTimeoutSeconds(),
          new PolicyResponse(
              assessment.policy().initialPostCancellationOrSuspensionRetentionStatus(),
              assessment.policy().initialPostCancellationOrSuspensionMonths(),
              assessment.policy().startMarker(),
              assessment.policy().categoryPolicies(),
              assessment.policy().legalBases(),
              assessment.policy().holdAuthority(),
              assessment.policy().holdInventory()),
          assessment.categories().stream().map(CategoryResponse::from).toList(),
          assessment.blockers());
    }
  }

  public record PolicyResponse(
      String initialPostCancellationOrSuspensionRetentionStatus,
      int initialPostCancellationOrSuspensionMonths,
      String startMarker,
      String categoryPolicies,
      String legalBases,
      String holdAuthority,
      String holdInventory) {}

  public record CategoryResponse(
      String code,
      String evaluationStatus,
      String sourceStatus,
      java.time.Instant observedAt,
      Map<String, RetentionAssessment.Count> counts,
      Map<String, String> facts,
      List<String> reasons) {

    static CategoryResponse from(RetentionAssessment.Category category) {
      return new CategoryResponse(
          category.code(),
          category.evaluationStatus(),
          category.sourceStatus(),
          category.observedAt(),
          category.counts(),
          category.facts(),
          category.reasons());
    }
  }
}
