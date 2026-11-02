package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.AdminBoard;
import dev.devfest.event.dto.EventSettingsRequest;
import dev.devfest.event.dto.EventStateView;
import dev.devfest.event.dto.PublicationRequest;
import dev.devfest.event.dto.RoomOpenView;
import dev.devfest.event.dto.RoundRequest;
import dev.devfest.event.dto.TimerAdjustRequest;
import dev.devfest.event.service.EventAdminBoardService;
import dev.devfest.event.service.EventControlService;
import dev.devfest.event.service.EventExportService;
import dev.devfest.event.service.EventRoundTimerService;
import jakarta.validation.Valid;
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

  public EventAdminController(
    EventAdminBoardService boards,
    EventControlService control,
    EventExportService export,
    EventRoundTimerService timer
  ) {
    this.boards = boards;
    this.control = control;
    this.export = export;
    this.timer = timer;
  }

  @PostMapping("/round/timer/start")
  EventStateView startTimer(@AuthenticationPrincipal AuthService.Principal principal) {
    return timer.start(principal);
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
