package dev.devfest.investigation;

import dev.devfest.admin.AdminController;
import dev.devfest.auth.AuthService.Principal;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/investigation")
public class InvestigationController {

  private final InvestigationOrganization organization;
  private final InvestigationChallenges challenges;
  private final InvestigationSubmissions submissions;
  private final InvestigationScoring scoring;

  public InvestigationController(
    InvestigationOrganization organization,
    InvestigationChallenges challenges,
    InvestigationSubmissions submissions,
    InvestigationScoring scoring
  ) {
    this.organization = organization;
    this.challenges = challenges;
    this.submissions = submissions;
    this.scoring = scoring;
  }

  @GetMapping("/rooms")
  Object rooms(@AuthenticationPrincipal Principal p) {
    return organization.rooms(p);
  }

  @GetMapping("/teams")
  Object teams(@AuthenticationPrincipal Principal p) {
    return organization.teams(p);
  }

  @GetMapping("/accounts")
  Object accounts(@AuthenticationPrincipal Principal p) {
    return organization.accounts(p);
  }

  @PostMapping("/accounts")
  Object account(
    @AuthenticationPrincipal Principal p,
    @Valid @RequestBody InvestigationOrganization.Account r
  ) {
    return Map.of("id", organization.account(p, r));
  }

  @PatchMapping("/accounts/{id}")
  void active(
    @AuthenticationPrincipal Principal p,
    @PathVariable long id,
    @RequestBody AdminController.Active r
  ) {
    organization.active(p, id, r.active());
  }

  @PostMapping("/accounts/{id}/password")
  void password(
    @AuthenticationPrincipal Principal p,
    @PathVariable long id,
    @Valid @RequestBody AdminController.Password r
  ) {
    organization.password(p, id, r.password());
  }

  @PostMapping("/rooms")
  Object room(
    @AuthenticationPrincipal Principal p,
    @Valid @RequestBody InvestigationOrganization.RoomEdit r
  ) {
    return Map.of("id", organization.room(p, null, r));
  }

  @PutMapping("/rooms/{id}")
  Object room(
    @AuthenticationPrincipal Principal p,
    @PathVariable long id,
    @Valid @RequestBody InvestigationOrganization.RoomEdit r
  ) {
    return Map.of("id", organization.room(p, id, r));
  }

  @PutMapping("/rooms/{id}/managers")
  void managers(
    @AuthenticationPrincipal Principal p,
    @PathVariable long id,
    @Valid @RequestBody InvestigationOrganization.Assignment r
  ) {
    organization.managers(p, id, r);
  }

  @PostMapping("/teams")
  Object team(
    @AuthenticationPrincipal Principal p,
    @Valid @RequestBody InvestigationOrganization.TeamEdit r
  ) {
    return Map.of("id", organization.team(p, null, r));
  }

  @PutMapping("/teams/{id}")
  Object team(
    @AuthenticationPrincipal Principal p,
    @PathVariable long id,
    @Valid @RequestBody InvestigationOrganization.TeamEdit r
  ) {
    return Map.of("id", organization.team(p, id, r));
  }

  @GetMapping("/preflight")
  Object preflight(@AuthenticationPrincipal Principal p) {
    return organization.preflight(p);
  }

  @GetMapping("/rooms/{room}/challenges")
  Object challenges(
    @AuthenticationPrincipal Principal p,
    @PathVariable long room
  ) {
    return challenges.list(p, room);
  }

  @PostMapping("/rooms/{room}/challenges")
  Object challenge(
    @AuthenticationPrincipal Principal p,
    @PathVariable long room,
    @Valid @RequestBody InvestigationChallenges.Edit r
  ) {
    return challenges.save(p, room, null, r);
  }

  @PutMapping("/rooms/{room}/challenges/{id}")
  Object challenge(
    @AuthenticationPrincipal Principal p,
    @PathVariable long room,
    @PathVariable long id,
    @Valid @RequestBody InvestigationChallenges.Edit r
  ) {
    return challenges.save(p, room, id, r);
  }

  @GetMapping("/challenges/{id}/draft")
  Object draft(@AuthenticationPrincipal Principal p, @PathVariable long id) {
    return submissions.draft(p, id);
  }

  @PutMapping("/challenges/{id}/draft")
  Object draft(
    @AuthenticationPrincipal Principal p,
    @PathVariable long id,
    @Valid @RequestBody InvestigationSubmissions.DraftEdit r
  ) {
    return submissions.saveDraft(p, id, r);
  }

  @PostMapping("/challenges/{id}/submissions")
  Object submit(
    @AuthenticationPrincipal Principal p,
    @PathVariable long id,
    @RequestHeader("Idempotency-Key") UUID key,
    @Valid @RequestBody InvestigationSubmissions.Report r
  ) {
    return submissions.submit(p, id, key, r);
  }

  @GetMapping("/rooms/{room}/submissions")
  Object submissions(
    @AuthenticationPrincipal Principal p,
    @PathVariable long room,
    @RequestParam(defaultValue = "0") int page
  ) {
    return submissions.list(p, room, page);
  }

  @GetMapping("/submissions/{id}")
  Object submission(
    @AuthenticationPrincipal Principal p,
    @PathVariable UUID id
  ) {
    return submissions.get(p, id);
  }

  @PutMapping("/submissions/{id}/score")
  void score(
    @AuthenticationPrincipal Principal p,
    @PathVariable UUID id,
    @Valid @RequestBody InvestigationScoring.ScoreEdit r
  ) {
    scoring.score(p, id, r);
  }

  @GetMapping("/submissions/{id}/scores")
  Object scores(@AuthenticationPrincipal Principal p, @PathVariable UUID id) {
    return scoring.history(p, id);
  }

  @GetMapping("/settings")
  Object settings() {
    return scoring.settings();
  }

  @PostMapping("/settings/confirm-rules")
  void confirm(@AuthenticationPrincipal Principal p) {
    scoring.confirm(p);
  }

  public record Publication(boolean published) {}

  @PutMapping("/settings/publication")
  void publication(
    @AuthenticationPrincipal Principal p,
    @RequestBody Publication r
  ) {
    scoring.publish(p, r.published());
  }

  @GetMapping("/rooms/{room}/results")
  Object results(
    @AuthenticationPrincipal Principal p,
    @PathVariable long room
  ) {
    return scoring.results(p, room);
  }
}
