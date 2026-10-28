package com.appagendamento.platform.api;

public interface DependencyMetrics {

  void configured(Dependency dependency, boolean configured);

  void observed(Dependency dependency, boolean available);

  enum Dependency {
    OUTBOX_DESTINATION("outbox_destination"),
    WHATSAPP("whatsapp"),
    PAYMENT_PROVIDER("payment_provider"),
    PAYMENT_RECONCILER("payment_reconciler"),
    EXPORT_STORAGE("export_storage");

    private final String tag;

    Dependency(String tag) {
      this.tag = tag;
    }

    public String tag() {
      return tag;
    }
  }
}
