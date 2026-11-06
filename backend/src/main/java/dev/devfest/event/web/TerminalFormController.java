package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.TerminalFormRequest;
import dev.devfest.event.dto.TerminalFormView;
import dev.devfest.event.service.TerminalFormService;
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

/** The team form of a LINUX_TERMINAL room for its team (MEMBER); the team always comes from the signed-in account. */
@RestController
@RequestMapping("/api/v1/event/team/rooms/{roomId}/terminal-form")
public class TerminalFormController {

  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private final TerminalFormService form;

  public TerminalFormController(TerminalFormService form) {
    this.form = form;
  }

  @GetMapping
  TerminalFormView view(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return form.view(principal, roomId);
  }

  @PostMapping("/save")
  TerminalFormView save(
    @PathVariable long roomId,
    @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
    @Valid @RequestBody TerminalFormRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return form.save(principal, roomId, idempotencyKey, request);
  }

  @PostMapping("/submit")
  TerminalFormView submit(
    @PathVariable long roomId,
    @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
    @Valid @RequestBody TerminalFormRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return form.submit(principal, roomId, idempotencyKey, request);
  }
}
