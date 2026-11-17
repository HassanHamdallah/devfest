package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.FinalActionResult;
import dev.devfest.event.dto.PassphraseRequest;
import dev.devfest.event.dto.SuspectChoiceRequest;
import dev.devfest.event.dto.UnlockRequest;
import dev.devfest.event.dto.Receipt;
import dev.devfest.event.dto.RecoveryRequest;
import dev.devfest.event.dto.RecoveryResult;
import dev.devfest.event.dto.SubmissionRequest;
import dev.devfest.event.dto.TeamBoard;
import dev.devfest.event.dto.TeamFinal;
import dev.devfest.event.service.EventFinalService;
import dev.devfest.event.service.EventSubmissionService;
import dev.devfest.event.service.EventTeamBoardService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** MEMBER endpoints; the team always comes from the signed-in account, never from the request. */
@RestController
@RequestMapping("/api/v1/event/team")
public class EventTeamController {

  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private final EventTeamBoardService boards;
  private final EventSubmissionService submissions;
  private final EventFinalService finals;

  public EventTeamController(
    EventTeamBoardService boards,
    EventSubmissionService submissions,
    EventFinalService finals
  ) {
    this.boards = boards;
    this.submissions = submissions;
    this.finals = finals;
  }

  @GetMapping("/board")
  TeamBoard board(@AuthenticationPrincipal AuthService.Principal principal) {
    return boards.board(principal);
  }

  @PostMapping("/rooms/{roomId}/submissions")
  Receipt submit(
    @PathVariable long roomId,
    @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
    @Valid @RequestBody SubmissionRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return submissions.submit(principal, roomId, idempotencyKey, request);
  }

  @GetMapping("/submissions")
  List<Receipt> submissions(@AuthenticationPrincipal AuthService.Principal principal) {
    return submissions.recent(principal);
  }

  @GetMapping("/submissions/{submissionId}")
  Receipt submission(@PathVariable UUID submissionId, @AuthenticationPrincipal AuthService.Principal principal) {
    return submissions.find(principal, submissionId);
  }

  @GetMapping("/final")
  TeamFinal teamFinal(@AuthenticationPrincipal AuthService.Principal principal) {
    return finals.view(principal);
  }

  @PostMapping("/final/recovery")
  RecoveryResult recover(
    @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
    @Valid @RequestBody RecoveryRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return finals.recover(principal, idempotencyKey, request);
  }

  @PostMapping("/final/unlock")
  FinalActionResult unlock(
    @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
    @Valid @RequestBody UnlockRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return finals.unlock(principal, idempotencyKey, request);
  }

  @PostMapping("/final/start")
  FinalActionResult startCase(@AuthenticationPrincipal AuthService.Principal principal) {
    return finals.startCase(principal);
  }

  /** One evidence file's passphrase (clues 1 to 4, any order). */
  @PostMapping("/final/clues/{clue}/passphrase")
  FinalActionResult submitPassphrase(
    @PathVariable int clue,
    @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
    @Valid @RequestBody PassphraseRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return finals.submitPassphrase(principal, clue, idempotencyKey, request);
  }

  @PostMapping("/final/suspect")
  FinalActionResult submitSuspect(
    @Valid @RequestBody SuspectChoiceRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return finals.submitSuspect(principal, request);
  }
}
