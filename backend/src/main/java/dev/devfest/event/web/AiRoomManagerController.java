package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.AiRoomResultsView;
import dev.devfest.event.dto.MissionEditorView;
import dev.devfest.event.dto.MissionSettingsRequest;
import dev.devfest.event.dto.RoomActivityRequest;
import dev.devfest.event.dto.RoomActivityView;
import dev.devfest.event.service.AiRoomContentService;
import dev.devfest.event.service.AiRoomResultsService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The AI mission room's content, import/export and results for the room's MANAGER (or ADMIN), and the per-room
 * activity switch (ADMIN, under /admin).
 */
@RestController
@RequestMapping("/api/v1/event")
public class AiRoomManagerController {

  private final AiRoomContentService content;
  private final AiRoomResultsService results;

  public AiRoomManagerController(AiRoomContentService content, AiRoomResultsService results) {
    this.content = content;
    this.results = results;
  }

  @GetMapping("/rooms/{roomId}/mission")
  MissionEditorView editor(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return content.editor(principal, roomId);
  }

  @PutMapping("/rooms/{roomId}/mission/settings")
  MissionEditorView saveSettings(
    @PathVariable long roomId,
    @Valid @RequestBody MissionSettingsRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return content.saveSettings(principal, roomId, request.passScore());
  }

  @GetMapping("/rooms/{roomId}/mission/export")
  JsonNode export(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return content.export(principal, roomId);
  }

  /** The body is the file's text as sent; a file that is not valid JSON is refused with the other problems' format. */
  @PostMapping("/rooms/{roomId}/mission/import")
  MissionEditorView importDocument(
    @PathVariable long roomId,
    @RequestBody String document,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return content.importDocument(principal, roomId, document);
  }

  @GetMapping("/rooms/{roomId}/mission/results")
  AiRoomResultsView results(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return results.results(principal, roomId);
  }

  @GetMapping(value = "/rooms/{roomId}/mission/results.csv", produces = "text/csv")
  String resultsCsv(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return results.exportCsv(principal, roomId);
  }

  @PutMapping("/admin/rooms/{roomId}/activity")
  RoomActivityView setActivity(
    @PathVariable long roomId,
    @RequestBody RoomActivityRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return content.setActivity(principal, roomId, request.activity());
  }
}
