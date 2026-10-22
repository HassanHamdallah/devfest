package dev.devfest.contest;

import dev.devfest.auth.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ContestController {

  private final ContestService service;
  private final RateLimit rate;

  public ContestController(ContestService s, RateLimit r) {
    service = s;
    rate = r;
  }

  @GetMapping("/contest")
  ContestService.Contest contest() {
    return service.contest();
  }

  @GetMapping("/languages")
  List<Map<String, Object>> languages() {
    return Arrays.stream(ProblemData.Language.values())
      .map(l -> Map.<String, Object>of("code", l.name(), "runtime", l.runtime))
      .toList();
  }

  @GetMapping("/problems")
  List<ProblemData.PublicProblem> problems(
    @RequestParam(defaultValue = "0") int page,
    @AuthenticationPrincipal AuthService.Principal p
  ) {
    visible(p);
    return service.problems(page);
  }

  @GetMapping("/problems/{id}")
  ProblemData.PublicProblem problem(
    @PathVariable long id,
    @AuthenticationPrincipal AuthService.Principal p
  ) {
    visible(p);
    int v = service.version(id);
    return service.problem(id, v).publicView(id, v);
  }

  private void visible(AuthService.Principal p) {
    if (
      !p.role().equals("ADMIN") &&
      service.contest().serverNow().isBefore(service.contest().startAt())
    ) throw dev.devfest.api.Errors.fail(
      409,
      "Problems open when the contest starts."
    );
  }

  @GetMapping("/scoreboard")
  List<ContestService.Score> scores() {
    return service.scores();
  }

  @GetMapping("/drafts/{problem}/{language}")
  ContestService.Draft draft(
    @AuthenticationPrincipal AuthService.Principal p,
    @PathVariable long problem,
    @PathVariable ProblemData.Language language
  ) {
    visible(p);
    return service.draft(p.id(), problem, language);
  }

  public record SaveDraft(
    @NotNull @Size(max = 65536) String source,
    @Min(0) long version
  ) {}

  @PutMapping("/drafts/{problem}/{language}")
  ContestService.Draft save(
    @AuthenticationPrincipal AuthService.Principal p,
    @PathVariable long problem,
    @PathVariable ProblemData.Language language,
    @Valid @RequestBody SaveDraft r
  ) {
    visible(p);
    rate.check("draft:" + p.id(), 60);
    return service.saveDraft(
      p.id(),
      problem,
      language,
      r.source(),
      r.version()
    );
  }
}
