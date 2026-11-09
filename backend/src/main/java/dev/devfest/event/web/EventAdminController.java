package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.AdminBoard;
import dev.devfest.event.dto.EventSettingsRequest;
import dev.devfest.event.dto.DeductionView;
import dev.devfest.event.dto.EventStateView;
import dev.devfest.event.dto.FinalAddTimeRequest;
import dev.devfest.event.dto.FinalBoardRow;
import dev.devfest.event.dto.FinalContentView;
import dev.devfest.event.dto.RankingRow;
import dev.devfest.event.dto.FinalTimerView;
import dev.devfest.event.dto.TeamScoreBreakdown;
import dev.devfest.event.dto.PublicationRequest;
import dev.devfest.event.dto.RoomOpenView;
import dev.devfest.event.dto.RoundRequest;
import dev.devfest.event.dto.TimerAdjustRequest;
import dev.devfest.event.dto.TimerStartRequest;
import dev.devfest.event.service.EventAdminBoardService;
import dev.devfest.event.service.EventControlService;
import dev.devfest.event.service.EventDeductionService;
import dev.devfest.event.service.EventFinalBoardService;
import dev.devfest.event.service.EventFinalContentService;
import dev.devfest.event.service.EventFinalTimerService;
import dev.devfest.event.service.ScoreBreakdownService;
import dev.devfest.event.service.EventExportService;
import dev.devfest.event.service.EventRoundTimerService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The organizer's event controls: board, rooms, rounds, final, publication and export. */
@RestController
@RequestMapping("/api/v1/event/admin")
public class EventAdminController {

  private final EventAdminBoardService boards;
  private final EventControlService control;
  private final EventExportService export;
  private final EventRoundTimerService timer;
  private final EventFinalTimerService finalTimer;
  private final EventFinalContentService finalContent;
  private final EventDeductionService deductions;
  private final ScoreBreakdownService scores;
  private final EventFinalBoardService scoreViews;

  public EventAdminController(
    EventAdminBoardService boards,
    EventControlService control,
    EventExportService export,
    EventRoundTimerService timer,
    EventFinalTimerService finalTimer,
    EventFinalContentService finalContent,
    EventDeductionService deductions,
    ScoreBreakdownService scores,
    EventFinalBoardService scoreViews
  ) {
    this.boards = boards;
    this.control = control;
    this.export = export;
    this.timer = timer;
    this.finalTimer = finalTimer;
    this.finalContent = finalContent;
    this.deductions = deductions;
    this.scores = scores;
    this.scoreViews = scoreViews;
  }

  /** The body is optional; {@code {"anyway": true}} starts although AI mission rooms are not ready. */
  @PostMapping("/round/timer/start")
  EventStateView startTimer(
    @RequestBody(required = false) TimerStartRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return timer.start(principal, request != null && request.startAnyway());
  }

  @PostMapping("/round/timer/pause")
  EventStateView pauseTimer(@AuthenticationPrincipal AuthService.Principal principal) {
    return timer.pause(principal);
  }

  @PostMapping("/round/timer/resume")
  EventStateView resumeTimer(@AuthenticationPrincipal AuthService.Principal principal) {
    return timer.resume(principal);
  }

  /** Not idempotent by nature: a repeated Idempotency-Key within the same round is ignored. */
  @PostMapping("/round/timer/adjust")
  EventStateView adjustTimer(
    @Valid @RequestBody TimerAdjustRequest request,
    @RequestHeader("Idempotency-Key") UUID idempotencyKey,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return timer.adjust(principal, request.deltaSeconds(), idempotencyKey);
  }

  @PostMapping("/round/timer/end")
  EventStateView endRound(@AuthenticationPrincipal AuthService.Principal principal) {
    return timer.end(principal);
  }

  @GetMapping("/board")
  AdminBoard board(@AuthenticationPrincipal AuthService.Principal principal) {
    return boards.board(principal);
  }

  @PostMapping("/rooms/{roomId}/open")
  RoomOpenView openRoom(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return control.setRoomOpen(principal, roomId, true);
  }

  @PostMapping("/rooms/{roomId}/close")
  RoomOpenView closeRoom(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return control.setRoomOpen(principal, roomId, false);
  }

  @PostMapping("/round")
  EventStateView setRound(
    @Valid @RequestBody RoundRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return control.setRound(principal, request.round());
  }

  @PutMapping("/settings")
  EventStateView saveSettings(
    @Valid @RequestBody EventSettingsRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return control.setRoundMinutes(principal, request.roundMinutes());
  }

  @PostMapping("/final/open")
  EventStateView openFinal(@AuthenticationPrincipal AuthService.Principal principal) {
    return control.openFinal(principal);
  }

  @PostMapping("/final/close")
  EventStateView closeFinal(@AuthenticationPrincipal AuthService.Principal principal) {
    return control.closeFinal(principal);
  }

  /** The one 40-minute timer of the final, for every team at once. */
  @GetMapping("/final/timer")
  FinalTimerView finalTimer(@AuthenticationPrincipal AuthService.Principal principal) {
    return finalTimer.view(principal);
  }

  @PostMapping("/final/timer/pause")
  FinalTimerView pauseFinal(@AuthenticationPrincipal AuthService.Principal principal) {
    return finalTimer.pause(principal);
  }

  @PostMapping("/final/timer/resume")
  FinalTimerView resumeFinal(@AuthenticationPrincipal AuthService.Principal principal) {
    return finalTimer.resume(principal);
  }

  /** Not idempotent by nature: a repeated Idempotency-Key is applied once. */
  @PostMapping("/final/timer/add")
  FinalTimerView addFinalTime(
    @Valid @RequestBody FinalAddTimeRequest request,
    @RequestHeader("Idempotency-Key") UUID idempotencyKey,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return finalTimer.addTime(principal, request.seconds(), idempotencyKey);
  }

  @GetMapping("/final/content")
  FinalContentView finalContent(@AuthenticationPrincipal AuthService.Principal principal) {
    return finalContent.view(principal);
  }

  /** The whole content file as JSON; read-only once the final has opened. */
  @PutMapping(value = "/final/content", consumes = "application/json")
  FinalContentView importFinalContent(@RequestBody String body, @AuthenticationPrincipal AuthService.Principal principal) {
    return finalContent.importContent(principal, body);
  }

  /** Every active team's full score breakdown, by team name. */
  @GetMapping("/scores")
  List<TeamScoreBreakdown> scores(@AuthenticationPrincipal AuthService.Principal principal) {
    return scores.all(principal);
  }

  /** The overall scoreboard: rank and the full breakdown of every active team (disqualified teams last, unranked). */
  @GetMapping("/scoreboard")
  List<RankingRow> scoreboard(@AuthenticationPrincipal AuthService.Principal principal) {
    return scoreViews.scoreboard(principal);
  }

  /** The live final table: status, stage, attempts, answers and the parts of the final score of every team. */
  @GetMapping("/final/board")
  List<FinalBoardRow> finalBoard(@AuthenticationPrincipal AuthService.Principal principal) {
    return scoreViews.finalTable(principal);
  }

  @GetMapping("/deductions")
  List<DeductionView> deductions(@AuthenticationPrincipal AuthService.Principal principal) {
    return deductions.fullLog(principal);
  }

  @PutMapping("/publication")
  EventStateView setPublication(
    @Valid @RequestBody PublicationRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return control.setResultsPublished(principal, request.published());
  }

  @GetMapping(value = "/export.csv", produces = "text/csv")
  String exportCsv(@AuthenticationPrincipal AuthService.Principal principal) {
    return export.exportCsv(principal);
  }
}
