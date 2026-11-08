package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.AiRoomActivityView;
import dev.devfest.event.dto.MissionRunRequest;
import dev.devfest.event.dto.MissionRunResult;
import dev.devfest.event.dto.MissionSavedReceipt;
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
import tools.jackson.databind.JsonNode;

/** The AI mission room for its team (MEMBER); the team always comes from the signed-in account. */
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

  @PostMapping("/stages/{stage}/runs")
  MissionRunResult run(
    @PathVariable long roomId,
    @PathVariable int stage,
    @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
    @Valid @RequestBody MissionRunRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return activity.run(principal, roomId, stage, idempotencyKey, request);
  }

  @PostMapping("/stages/{stage}/submit")
  MissionSavedReceipt submit(
    @PathVariable long roomId,
    @PathVariable int stage,
    @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
    @RequestBody JsonNode answer,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return activity.submit(principal, roomId, stage, idempotencyKey, answer);
  }
}
