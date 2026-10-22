package dev.devfest.submission;

import dev.devfest.auth.*;
import dev.devfest.contest.ProblemData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SubmissionController {

  private final SubmissionService service;
  private final RateLimit rate;

  public SubmissionController(SubmissionService s, RateLimit r) {
    service = s;
    rate = r;
  }

  public record Request(
    @Positive long problemId,
    @NotNull ProblemData.Language language,
    @NotBlank @Size(max = 65536) String source,
    @NotNull @Size(max = 65536) String stdin
  ) {}

  @PostMapping({ "/runs", "/submissions" })
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  Map<String, UUID> create(
    @AuthenticationPrincipal AuthService.Principal p,
    @RequestHeader("Idempotency-Key") UUID key,
    @Valid @RequestBody Request r,
    jakarta.servlet.http.HttpServletRequest req
  ) {
    rate.check("execute:" + p.id(), 15);
    String kind = req.getRequestURI().endsWith("/runs") ? "RUN" : "SUBMIT";
    return Map.of(
      "id",
      service.create(
        p.id(),
        kind,
        key,
        new SubmissionService.Create(
          r.problemId(),
          r.language(),
          r.source(),
          r.stdin()
        )
      )
    );
  }

  @GetMapping("/submissions")
  List<SubmissionService.View> list(
    @AuthenticationPrincipal AuthService.Principal p,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "") String state
  ) {
    return service.list(p, page, state);
  }

  @GetMapping("/submissions/{id}")
  SubmissionService.View get(
    @AuthenticationPrincipal AuthService.Principal p,
    @PathVariable UUID id
  ) {
    return service.get(id, p);
  }

  @GetMapping("/submissions/{id}/history")
  List<SubmissionService.History> history(
    @AuthenticationPrincipal AuthService.Principal p,
    @PathVariable UUID id
  ) {
    return service.history(id, p);
  }
}
