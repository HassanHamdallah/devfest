package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.TerminalFileContent;
import dev.devfest.event.dto.TerminalFileList;
import dev.devfest.event.dto.TerminalFileRequest;
import dev.devfest.event.dto.TerminalSettingRequest;
import dev.devfest.event.dto.TerminalSettingView;
import dev.devfest.event.dto.TerminalStartFiles;
import dev.devfest.event.service.EventTerminalService;
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
 * The in-browser terminal: the assigned team's files (MEMBER), the room's file management (room MANAGER or ADMIN)
 * and the per-room switch (ADMIN, under /admin).
 */
@RestController
@RequestMapping("/api/v1/event")
public class EventTerminalController {

  private final EventTerminalService terminal;

  public EventTerminalController(EventTerminalService terminal) {
    this.terminal = terminal;
  }

  @GetMapping("/team/rooms/{roomId}/terminal-files")
  TerminalStartFiles teamFiles(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return terminal.teamFiles(principal, roomId);
  }

  @GetMapping("/rooms/{roomId}/terminal-files")
  TerminalFileList list(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return terminal.list(principal, roomId);
  }

  @GetMapping("/rooms/{roomId}/terminal-files/content")
  TerminalStartFiles managerFiles(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return terminal.managerFiles(principal, roomId);
  }

  @GetMapping("/rooms/{roomId}/terminal-files/{fileId}")
  TerminalFileContent file(
    @PathVariable long roomId,
    @PathVariable long fileId,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return terminal.file(principal, roomId, fileId);
  }

  @PostMapping("/rooms/{roomId}/terminal-files")
  TerminalFileList create(
    @PathVariable long roomId,
    @Valid @RequestBody TerminalFileRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return terminal.create(principal, roomId, request);
  }

  @PutMapping("/rooms/{roomId}/terminal-files/{fileId}")
  TerminalFileList update(
    @PathVariable long roomId,
    @PathVariable long fileId,
    @Valid @RequestBody TerminalFileRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return terminal.update(principal, roomId, fileId, request);
  }

  @DeleteMapping("/rooms/{roomId}/terminal-files/{fileId}")
  TerminalFileList delete(
    @PathVariable long roomId,
    @PathVariable long fileId,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return terminal.delete(principal, roomId, fileId);
  }

  @PutMapping("/admin/rooms/{roomId}/terminal")
  TerminalSettingView setEnabled(
    @PathVariable long roomId,
    @Valid @RequestBody TerminalSettingRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return terminal.setEnabled(principal, roomId, request.enabled());
  }
}
