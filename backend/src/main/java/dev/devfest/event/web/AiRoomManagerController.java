package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.AiRoomResultsView;
import dev.devfest.event.dto.QuizDocument;
import dev.devfest.event.dto.QuizEditorView;
import dev.devfest.event.dto.QuizQuestionRequest;
import dev.devfest.event.dto.QuizRepairRequest;
import dev.devfest.event.dto.QuizSettingsRequest;
import dev.devfest.event.dto.RoomActivityRequest;
import dev.devfest.event.dto.RoomActivityView;
import dev.devfest.event.service.AiRoomContentService;
import dev.devfest.event.service.AiRoomResultsService;
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
 * The AI room's content, import/export and results for the room's MANAGER (or ADMIN), and the per-room activity
 * switch (ADMIN, under /admin).
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

  @GetMapping("/rooms/{roomId}/quiz")
  QuizEditorView editor(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return content.editor(principal, roomId);
  }

  @PostMapping("/rooms/{roomId}/quiz/questions")
  QuizEditorView createQuestion(
    @PathVariable long roomId,
    @RequestBody QuizQuestionRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return content.createQuestion(principal, roomId, request);
  }

  @PutMapping("/rooms/{roomId}/quiz/questions/{questionId}")
  QuizEditorView updateQuestion(
    @PathVariable long roomId,
    @PathVariable long questionId,
    @RequestBody QuizQuestionRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return content.updateQuestion(principal, roomId, questionId, request);
  }

  @DeleteMapping("/rooms/{roomId}/quiz/questions/{questionId}")
  QuizEditorView deleteQuestion(
    @PathVariable long roomId,
    @PathVariable long questionId,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return content.deleteQuestion(principal, roomId, questionId);
  }

  @PostMapping("/rooms/{roomId}/quiz/repairs")
  QuizEditorView createRepair(
    @PathVariable long roomId,
    @RequestBody QuizRepairRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return content.createRepair(principal, roomId, request);
  }

  @PutMapping("/rooms/{roomId}/quiz/repairs/{repairId}")
  QuizEditorView updateRepair(
    @PathVariable long roomId,
    @PathVariable long repairId,
    @RequestBody QuizRepairRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return content.updateRepair(principal, roomId, repairId, request);
  }

  @DeleteMapping("/rooms/{roomId}/quiz/repairs/{repairId}")
  QuizEditorView deleteRepair(
    @PathVariable long roomId,
    @PathVariable long repairId,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return content.deleteRepair(principal, roomId, repairId);
  }

  @PutMapping("/rooms/{roomId}/quiz/settings")
  QuizEditorView saveSettings(
    @PathVariable long roomId,
    @Valid @RequestBody QuizSettingsRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return content.saveSettings(principal, roomId, request.mcqMinutes(), request.passScore());
  }

  @GetMapping("/rooms/{roomId}/quiz/export")
  QuizDocument export(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return content.export(principal, roomId);
  }

  @PostMapping("/rooms/{roomId}/quiz/import")
  QuizEditorView importDocument(
    @PathVariable long roomId,
    @RequestBody QuizDocument document,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return content.importDocument(principal, roomId, document);
  }

  @GetMapping("/rooms/{roomId}/quiz/results")
  AiRoomResultsView results(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return results.results(principal, roomId);
  }

  @GetMapping(value = "/rooms/{roomId}/quiz/results.csv", produces = "text/csv")
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
