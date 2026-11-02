package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.AiRoomActivityView;
import dev.devfest.event.dto.QuizAnswerRequest;
import dev.devfest.event.dto.QuizRepairOrderRequest;
import dev.devfest.event.dto.QuizSavedReceipt;
import dev.devfest.event.service.AiRoomActivityService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The AI room activity for its team (MEMBER); the team always comes from the signed-in account. */
@RestController
@RequestMapping("/api/v1/event/team/rooms/{roomId}/activity")
public class AiRoomTeamController {

  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private final AiRoomActivityService activity;

  public AiRoomTeamController(AiRoomActivityService activity) {
    this.activity = activity;
  }

  @GetMapping
  AiRoomActivityView view(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return activity.view(principal, roomId);
  }

  @PostMapping("/answers")
  QuizSavedReceipt answer(
    @PathVariable long roomId,
    @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
    @Valid @RequestBody QuizAnswerRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return activity.answer(principal, roomId, idempotencyKey, request);
  }

  @PostMapping("/repairs/{repairId}")
  QuizSavedReceipt submitRepair(
    @PathVariable long roomId,
    @PathVariable long repairId,
    @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
    @Valid @RequestBody QuizRepairOrderRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return activity.submitRepair(principal, roomId, repairId, idempotencyKey, request);
  }

  @PostMapping("/finish-mcq")
  AiRoomActivityView finishMcq(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return activity.finishMcq(principal, roomId);
  }
}
