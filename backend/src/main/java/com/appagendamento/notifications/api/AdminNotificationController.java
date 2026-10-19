package com.appagendamento.notifications.api;

import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.notifications.application.GetNotificationOperations;
import com.appagendamento.notifications.application.NotificationRepository.AttemptOverview;
import com.appagendamento.notifications.application.NotificationRepository.NotificationDetail;
import com.appagendamento.notifications.application.NotificationRepository.NotificationOverview;
import com.appagendamento.notifications.application.NotificationRepository.NotificationSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminNotificationController {

  private final GetNotificationOperations operations;

  public AdminNotificationController(GetNotificationOperations operations) {
    this.operations = operations;
  }

  @GetMapping("/api/v1/admin/notifications/summary")
  ResponseEntity<NotificationSummaryResponse> summary(
      @AuthenticationPrincipal AuthenticatedAdmin admin) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new NotificationSummaryResponse(operations.summary(admin)));
  }

  @GetMapping("/api/v1/admin/notifications")
  ResponseEntity<NotificationListResponse> list(
      @AuthenticationPrincipal AuthenticatedAdmin admin,
      @RequestParam(defaultValue = "50") int limit) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new NotificationListResponse(operations.list(admin, limit)));
  }

  @GetMapping("/api/v1/admin/notifications/{notificationId}")
  ResponseEntity<NotificationDetailResponse> detail(
      @AuthenticationPrincipal AuthenticatedAdmin admin, @PathVariable UUID notificationId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(NotificationDetailResponse.from(operations.detail(admin, notificationId)));
  }

  public record NotificationSummaryResponse(List<NotificationSummary> states) {}

  public record NotificationListResponse(List<NotificationOverview> notifications) {}

  public record NotificationDetailResponse(
      NotificationOverview notification, List<AttemptOverview> attempts) {
    static NotificationDetailResponse from(NotificationDetail detail) {
      return new NotificationDetailResponse(detail.overview(), detail.attempts());
    }
  }
}
