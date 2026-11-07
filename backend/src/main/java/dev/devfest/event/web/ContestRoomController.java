package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.ContestLinkRequest;
import dev.devfest.event.dto.ContestResultRequest;
import dev.devfest.event.dto.ContestResultsView;
import dev.devfest.event.dto.ContestTeamView;
import dev.devfest.event.service.ContestReleaseService;
import dev.devfest.event.service.ContestResultsService;
import dev.devfest.event.service.ContestTeamService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Problem Solving room: the organizer page (results, link, release) for the room's MANAGER and the ADMIN, where
 * everyone else, members included, gets a 404; and the team's own view of the contest link for its team (MEMBER).
 */
@RestController
@RequestMapping("/api/v1/event")
public class ContestRoomController {

  private final ContestResultsService results;
  private final ContestReleaseService release;
  private final ContestTeamService team;

  public ContestRoomController(ContestResultsService results, ContestReleaseService release, ContestTeamService team) {
    this.results = results;
    this.release = release;
    this.team = team;
  }

  @GetMapping("/rooms/{roomId}/contest-results")
  ContestResultsView view(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return results.view(principal, roomId);
  }

  @GetMapping(value = "/rooms/{roomId}/contest-results.csv", produces = "text/csv")
  String csv(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return results.exportCsv(principal, roomId);
  }

  @PutMapping("/rooms/{roomId}/contest-results/link")
  ContestResultsView saveLink(
    @PathVariable long roomId,
    @Valid @RequestBody ContestLinkRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return results.saveLink(principal, roomId, request);
  }

  @DeleteMapping("/rooms/{roomId}/contest-results/link")
  ContestResultsView clearLink(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return results.clearLink(principal, roomId);
  }

  @PutMapping("/rooms/{roomId}/contest-results/teams/{teamId}")
  ContestResultsView saveResult(
    @PathVariable long roomId,
    @PathVariable long teamId,
    @Valid @RequestBody ContestResultRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return results.saveResult(principal, roomId, teamId, request);
  }

  @PostMapping("/rooms/{roomId}/contest-results/teams/{teamId}/reveal")
  ContestResultsView reveal(
    @PathVariable long roomId,
    @PathVariable long teamId,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return release.reveal(principal, roomId, teamId);
  }

  @PostMapping("/rooms/{roomId}/contest-results/rounds/{round}/reveal")
  ContestResultsView revealRound(
    @PathVariable long roomId,
    @PathVariable int round,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return release.revealRound(principal, roomId, round);
  }

  @GetMapping("/team/rooms/{roomId}/contest")
  ContestTeamView teamView(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return team.view(principal, roomId);
  }
}
