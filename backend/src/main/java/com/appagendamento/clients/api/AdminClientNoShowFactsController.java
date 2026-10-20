package com.appagendamento.clients.api;

import com.appagendamento.clients.application.ClientNoShowFacts;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.time.Instant;
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
public class AdminClientNoShowFactsController {

  private final ClientNoShowFacts facts;

  public AdminClientNoShowFactsController(ClientNoShowFacts facts) {
    this.facts = facts;
  }

  @GetMapping("/api/v1/admin/clients/{id}/no-shows")
  ResponseEntity<Response> noShows(
      @AuthenticationPrincipal AuthenticatedAdmin actor,
      @PathVariable UUID id,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(Response.from(facts.execute(actor, id, cursor, limit)));
  }

  record Response(
      UUID clientId,
      Instant windowStart,
      Instant asOf,
      String timeZone,
      long count,
      long totalFacts,
      String policyStatus,
      Integer threshold,
      long policyVersion,
      String restrictionStatus,
      ClientBookingRestrictionQuery.ClientBookingRestriction activeRestriction,
      List<ClientBookingRestrictionQuery.ClientBookingRestrictionHistory> restrictionHistory,
      List<Fact> facts,
      String nextCursor,
      boolean hasMore) {

    static Response from(ClientNoShowFacts.Result result) {
      return new Response(
          result.clientId(),
          result.windowStart(),
          result.asOf(),
          result.timeZone(),
          result.count(),
          result.totalFacts(),
          result.policyStatus(),
          result.threshold(),
          result.policyVersion(),
          result.restrictionStatus(),
          result.activeRestriction(),
          result.restrictionHistory(),
          result.facts().stream().map(Fact::from).toList(),
          result.nextCursor(),
          result.hasMore());
    }
  }

  record Fact(
      UUID historyId,
      UUID appointmentId,
      Instant recordedAt,
      String currentStatus,
      long appointmentVersion,
      boolean contributes,
      UUID correctionHistoryId,
      Instant correctedAt,
      String correctionReason) {

    static Fact from(ClientNoShowFactsQuery.Fact fact) {
      return new Fact(
          fact.historyId(),
          fact.appointmentId(),
          fact.recordedAt(),
          fact.currentStatus(),
          fact.appointmentVersion(),
          fact.contributes(),
          fact.correctionHistoryId(),
          fact.correctedAt(),
          fact.correctionReason());
    }
  }
}
