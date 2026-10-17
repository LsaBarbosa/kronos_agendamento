package com.appagendamento.clients.api;

import com.appagendamento.clients.application.SearchAdministrativeClients;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminClientController {

  private final SearchAdministrativeClients searchClients;

  public AdminClientController(SearchAdministrativeClients searchClients) {
    this.searchClients = searchClients;
  }

  @GetMapping("/api/v1/admin/clients")
  ResponseEntity<List<ClientSearchResponse>> search(
      @AuthenticationPrincipal AuthenticatedAdmin actor, @RequestParam String query) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            searchClients.execute(actor, query).stream().map(ClientSearchResponse::from).toList());
  }

  public record ClientSearchResponse(String clientId, String displayName) {
    static ClientSearchResponse from(AdministrativeClientRegistry.AdministrativeClientView client) {
      return new ClientSearchResponse(client.clientId().toString(), client.displayName());
    }
  }
}
