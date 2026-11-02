package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.JudgementRequest;
import dev.devfest.event.dto.JudgementView;
import dev.devfest.event.dto.PenaltyRequest;
import dev.devfest.event.dto.PenaltyView;
import dev.devfest.event.dto.RoomBoard;
import dev.devfest.event.dto.RoomContentRequest;
import dev.devfest.event.dto.RoomContentView;
import dev.devfest.event.dto.VoidPenaltyRequest;
import dev.devfest.event.service.EventContentService;
import dev.devfest.event.service.EventJudgingService;
import dev.devfest.event.service.EventPenaltyService;
import dev.devfest.event.service.EventRoomBoardService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Room endpoints for the room's MANAGER; ADMIN may use them for any room. */
@RestController
@RequestMapping("/api/v1/event")
public class EventManagerController {

  private final EventRoomBoardService boards;
  private final EventJudgingService judging;
  private final EventPenaltyService penalties;
  private final EventContentService content;

  public EventManagerController(
    EventRoomBoardService boards,
    EventJudgingService judging,
    EventPenaltyService penalties,
    EventContentService content
  ) {
    this.boards = boards;
    this.judging = judging;
    this.penalties = penalties;
    this.content = content;
  }

  @GetMapping("/rooms/{roomId}/board")
  RoomBoard board(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return boards.board(principal, roomId);
  }

  @PutMapping("/rooms/{roomId}/judgements/{teamId}")
  JudgementView judge(
    @PathVariable long roomId,
    @PathVariable long teamId,
    @Valid @RequestBody JudgementRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return judging.judge(principal, roomId, teamId, request);
  }

  @PostMapping("/rooms/{roomId}/penalties")
  PenaltyView addPenalty(
    @PathVariable long roomId,
    @Valid @RequestBody PenaltyRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return penalties.add(principal, roomId, request);
  }

  @PostMapping("/penalties/{penaltyId}/void")
  PenaltyView voidPenalty(
    @PathVariable long penaltyId,
    @Valid @RequestBody VoidPenaltyRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return penalties.voidPenalty(principal, penaltyId, request);
  }

  @PutMapping("/rooms/{roomId}/content")
  RoomContentView updateContent(
    @PathVariable long roomId,
    @Valid @RequestBody RoomContentRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return content.updateRoomContent(principal, roomId, request);
  }
}
