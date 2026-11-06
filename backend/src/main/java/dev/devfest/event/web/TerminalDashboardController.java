package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.domain.TerminalScoreItem;
import dev.devfest.event.dto.TerminalDashboardView;
import dev.devfest.event.dto.TerminalKeysRequest;
import dev.devfest.event.dto.TerminalOverrideRequest;
import dev.devfest.event.service.TerminalDashboardService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The organizer dashboard of a Linux terminal room: the room's MANAGER and the ADMIN only; everyone else, members
 * included, gets a 404 (the page carries answer keys).
 */
@RestController
@RequestMapping("/api/v1/event/rooms/{roomId}")
public class TerminalDashboardController {

  private final TerminalDashboardService dashboard;

  public TerminalDashboardController(TerminalDashboardService dashboard) {
    this.dashboard = dashboard;
  }

  @GetMapping("/terminal-dashboard")
  TerminalDashboardView view(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return dashboard.view(principal, roomId);
  }

  @GetMapping(value = "/terminal-dashboard.csv", produces = "text/csv")
  String csv(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return dashboard.exportCsv(principal, roomId);
  }

  @PutMapping("/terminal-dashboard/keys")
  TerminalDashboardView saveKeys(
    @PathVariable long roomId,
    @Valid @RequestBody TerminalKeysRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return dashboard.saveKeys(principal, roomId, request);
  }

  @PutMapping("/terminal-dashboard/teams/{teamId}/items/{item}")
  TerminalDashboardView setOverride(
    @PathVariable long roomId,
    @PathVariable long teamId,
    @PathVariable TerminalScoreItem item,
    @RequestBody TerminalOverrideRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return dashboard.setOverride(principal, roomId, teamId, item, request.awarded());
  }
}
