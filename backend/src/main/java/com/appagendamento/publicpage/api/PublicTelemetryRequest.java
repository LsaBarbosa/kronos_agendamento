package com.appagendamento.publicpage.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class PublicTelemetryRequest {

  private String event;

  public PublicTelemetryRequest() {}

  @JsonProperty
  public String event() {
    return event;
  }

  @JsonProperty
  public void setEvent(String event) {
    this.event = event;
  }

  @JsonAnySetter
  public void rejectAdditionalProperty(String name, Object value) {
    throw new IllegalArgumentException("Propriedade de telemetria não permitida: " + name);
  }
}
