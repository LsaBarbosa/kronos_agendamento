package com.appagendamento.notifications.application;

import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.notifications.application.NotificationRepository.NotificationDetail;
import com.appagendamento.notifications.application.NotificationRepository.NotificationOverview;
import com.appagendamento.notifications.application.NotificationRepository.NotificationSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GetNotificationOperations {

  private final AdminAccessPolicy access;
  private final NotificationRepository notifications;

  public GetNotificationOperations(AdminAccessPolicy access, NotificationRepository notifications) {
    this.access = access;
    this.notifications = notifications;
  }

  public List<NotificationSummary> summary(AuthenticatedAdmin admin) {
    authorize(admin);
    return notifications.summarize(admin.tenantId());
  }

  public List<NotificationOverview> list(AuthenticatedAdmin admin, int limit) {
    authorize(admin);
    if (limit < 1 || limit > 100) {
      throw new InvalidNotificationQueryException();
    }
    return notifications.list(admin.tenantId(), limit);
  }

  public NotificationDetail detail(AuthenticatedAdmin admin, UUID notificationId) {
    authorize(admin);
    return notifications
        .detail(admin.tenantId(), notificationId)
        .orElseThrow(NotificationNotFoundException::new);
  }

  private void authorize(AuthenticatedAdmin admin) {
    if (admin == null || !"TENANT_ADMIN".equals(admin.role()) || !access.isCurrent(admin)) {
      throw new NotificationAdminAccessDeniedException();
    }
  }
}
