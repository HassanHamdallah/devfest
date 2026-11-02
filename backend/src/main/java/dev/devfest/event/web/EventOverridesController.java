package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.ManualClueRequest;
import dev.devfest.event.dto.MovePreviewRequest;
import dev.devfest.event.dto.MoveTeamRequest;
import dev.devfest.event.dto.OverrideOutcome;
import dev.devfest.event.dto.OverrideReasonRequest;
import dev.devfest.event.dto.ResetEventRequest;
import dev.devfest.event.dto.RoomExceptionPreviewRequest;
import dev.devfest.event.dto.RoomExceptionRequest;
import dev.devfest.event.dto.RotationPreviewView;
import dev.devfest.event.dto.SnapshotView;
import dev.devfest.event.dto.SwapPreviewRequest;
import dev.devfest.event.dto.SwapRequest;
import dev.devfest.event.dto.TimeAdjustmentRequest;
import dev.devfest.event.service.EventOverrideService;
import dev.devfest.event.service.EventResetService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The organizer's overrides, resets and snapshots (ADMIN only; every change carries a reason). */
@RestController
@RequestMapping("/api/v1/event/admin")
public class EventOverridesController {

  private final EventOverrideService overrides;
  private final EventResetService resets;

  public EventOverridesController(EventOverrideService overrides, EventResetService resets) {
    this.overrides = overrides;
    this.resets = resets;
  }

  @PostMapping("/overrides/swap-preview")
  List<RotationPreviewView> swapPreview(
    @Valid @RequestBody SwapPreviewRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return overrides.swapPreview(principal, request.teamA(), request.teamB());
  }

  @PostMapping("/overrides/swap")
  OverrideOutcome swap(@Valid @RequestBody SwapRequest request, @AuthenticationPrincipal AuthService.Principal principal) {
    return overrides.swap(principal, request);
  }

  @PostMapping("/overrides/move-preview")
  List<RotationPreviewView> movePreview(
    @Valid @RequestBody MovePreviewRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return overrides.movePreview(principal, request.teamId(), request.group());
  }

  @PutMapping("/teams/{teamId}/group")
  OverrideOutcome move(
    @PathVariable long teamId,
    @Valid @RequestBody MoveTeamRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return overrides.move(principal, teamId, request);
  }

  /** Warnings only (room closed, no key, clue already collected); nothing changes. */
  @PostMapping("/overrides/room-exception/preview")
  List<String> roomExceptionPreview(
    @Valid @RequestBody RoomExceptionPreviewRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return overrides.roomExceptionPreview(principal, request.teamId(), request.roomId());
  }

  @PutMapping("/overrides/room-exception")
  OverrideOutcome setRoomException(
    @Valid @RequestBody RoomExceptionRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return overrides.setRoomException(principal, request);
  }

  @DeleteMapping("/overrides/room-exception/{teamId}")
  OverrideOutcome clearRoomException(
    @PathVariable long teamId,
    @Valid @RequestBody OverrideReasonRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return overrides.clearRoomException(principal, teamId, request.reason());
  }

  @PostMapping("/overrides/clue")
  OverrideOutcome clue(@Valid @RequestBody ManualClueRequest request, @AuthenticationPrincipal AuthService.Principal principal) {
    return overrides.clue(principal, request);
  }

  @PostMapping("/overrides/time-adjustment")
  OverrideOutcome timeAdjustment(
    @Valid @RequestBody TimeAdjustmentRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return overrides.timeAdjustment(principal, request);
  }

  @PostMapping("/teams/{teamId}/disqualify")
  OverrideOutcome disqualify(
    @PathVariable long teamId,
    @Valid @RequestBody OverrideReasonRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return overrides.setDisqualified(principal, teamId, true, request.reason());
  }

  @PostMapping("/teams/{teamId}/reinstate")
  OverrideOutcome reinstate(
    @PathVariable long teamId,
    @Valid @RequestBody OverrideReasonRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return overrides.setDisqualified(principal, teamId, false, request.reason());
  }

  @PostMapping("/reset/final")
  OverrideOutcome resetFinal(
    @Valid @RequestBody OverrideReasonRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return resets.resetFinal(principal, request.reason());
  }

  @PostMapping("/reset/team/{teamId}")
  OverrideOutcome resetTeam(
    @PathVariable long teamId,
    @Valid @RequestBody OverrideReasonRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return resets.resetTeam(principal, teamId, request.reason());
  }

  /** The password is checked server-side and never logged. */
  @PostMapping("/reset/event")
  OverrideOutcome resetEvent(
    @Valid @RequestBody ResetEventRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return resets.resetEvent(principal, request);
  }

  @PostMapping("/snapshots")
  OverrideOutcome snapshot(
    @Valid @RequestBody OverrideReasonRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return resets.snapshot(principal, request.reason());
  }

  @GetMapping("/snapshots")
  List<SnapshotView> snapshots(@AuthenticationPrincipal AuthService.Principal principal) {
    return resets.snapshots(principal);
  }

  @GetMapping("/snapshots/{snapshotId}/download")
  ResponseEntity<String> download(@PathVariable long snapshotId, @AuthenticationPrincipal AuthService.Principal principal) {
    return ResponseEntity
      .ok()
      .contentType(MediaType.APPLICATION_JSON)
      .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"event-snapshot-" + snapshotId + ".json\"")
      .body(resets.download(principal, snapshotId));
  }
}
