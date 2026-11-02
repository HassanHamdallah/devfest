package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.AccountActiveRequest;
import dev.devfest.event.dto.AccountPasswordRequest;
import dev.devfest.event.dto.AccountView;
import dev.devfest.event.dto.AdminContent;
import dev.devfest.event.dto.AdminContentRequest;
import dev.devfest.event.dto.CreateAccountRequest;
import dev.devfest.event.dto.CreateTeamRequest;
import dev.devfest.event.dto.GenerateTeamsRequest;
import dev.devfest.event.dto.Organization;
import dev.devfest.event.dto.PlannedTeamsRequest;
import dev.devfest.event.dto.RoomManagerRequest;
import dev.devfest.event.dto.RoomManagerView;
import dev.devfest.event.dto.SuspectRequest;
import dev.devfest.event.dto.SuspectView;
import dev.devfest.event.dto.TeamCredential;
import dev.devfest.event.dto.TeamView;
import dev.devfest.event.dto.UpdateTeamRequest;
import dev.devfest.event.service.EventContentService;
import dev.devfest.event.service.EventOrganizationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The organizer's setup screens: accounts, teams, room managers, event content and suspects. */
@RestController
@RequestMapping("/api/v1/event/admin")
public class EventSetupController {

  private final EventOrganizationService organization;
  private final EventContentService content;

  public EventSetupController(EventOrganizationService organization, EventContentService content) {
    this.organization = organization;
    this.content = content;
  }

  @GetMapping("/organization")
  Organization organization(@AuthenticationPrincipal AuthService.Principal principal) {
    return organization.organization(principal);
  }

  @PostMapping("/accounts")
  AccountView createAccount(
    @Valid @RequestBody CreateAccountRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return organization.createAccount(principal, request);
  }

  @PutMapping("/accounts/{accountId}/active")
  AccountView setAccountActive(
    @PathVariable long accountId,
    @Valid @RequestBody AccountActiveRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return organization.setAccountActive(principal, accountId, request.active());
  }

  @PostMapping("/accounts/{accountId}/password")
  AccountView resetPassword(
    @PathVariable long accountId,
    @Valid @RequestBody AccountPasswordRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return organization.resetPassword(principal, accountId, request.password());
  }

  @PostMapping("/teams")
  TeamView createTeam(
    @Valid @RequestBody CreateTeamRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return organization.createTeam(principal, request);
  }

  @PutMapping("/teams/{teamId}")
  TeamView updateTeam(
    @PathVariable long teamId,
    @Valid @RequestBody UpdateTeamRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return organization.updateTeam(principal, teamId, request);
  }

  @PutMapping("/teams/planned-count")
  Organization setPlannedTeamCount(
    @Valid @RequestBody PlannedTeamsRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return organization.setPlannedTeamCount(principal, request.plannedCount());
  }

  /** The passwords exist only in this response; they are not stored or logged. */
  @PostMapping("/teams/generate")
  List<TeamCredential> generateTeams(
    @Valid @RequestBody GenerateTeamsRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return organization.generateTeams(principal, request);
  }

  @PostMapping("/teams/{teamId}/sign-out")
  TeamView signOutTeam(@PathVariable long teamId, @AuthenticationPrincipal AuthService.Principal principal) {
    return organization.signOutTeam(principal, teamId);
  }

  @PutMapping("/rooms/{roomId}/manager")
  RoomManagerView assignManager(
    @PathVariable long roomId,
    @Valid @RequestBody RoomManagerRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return organization.assignManager(principal, roomId, request.managerId());
  }

  @GetMapping("/content")
  AdminContent content(@AuthenticationPrincipal AuthService.Principal principal) {
    return content.adminContent(principal);
  }

  @PutMapping("/content")
  AdminContent updateContent(
    @Valid @RequestBody AdminContentRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return content.updateAdminContent(principal, request);
  }

  @PostMapping("/suspects")
  SuspectView createSuspect(
    @Valid @RequestBody SuspectRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return content.createSuspect(principal, request);
  }

  @PutMapping("/suspects/{suspectId}")
  SuspectView updateSuspect(
    @PathVariable long suspectId,
    @Valid @RequestBody SuspectRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return content.updateSuspect(principal, suspectId, request);
  }

  @DeleteMapping("/suspects/{suspectId}")
  AdminContent deleteSuspect(@PathVariable long suspectId, @AuthenticationPrincipal AuthService.Principal principal) {
    return content.deleteSuspect(principal, suspectId);
  }
}
