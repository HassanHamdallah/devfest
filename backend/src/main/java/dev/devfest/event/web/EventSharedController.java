package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.EventHome;
import dev.devfest.event.dto.ResultsView;
import dev.devfest.event.dto.RevisionView;
import dev.devfest.event.service.EventResultsService;
import dev.devfest.event.service.EventSharedService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints every event role calls. */
@RestController
@RequestMapping("/api/v1/event")
public class EventSharedController {

  private final EventSharedService shared;
  private final EventResultsService results;

  public EventSharedController(EventSharedService shared, EventResultsService results) {
    this.shared = shared;
    this.results = results;
  }

  @GetMapping("/revision")
  RevisionView revision(@AuthenticationPrincipal AuthService.Principal principal) {
    return shared.revision(principal);
  }

  @GetMapping("/home")
  EventHome home(@AuthenticationPrincipal AuthService.Principal principal) {
    return shared.home(principal);
  }

  @GetMapping("/results")
  ResultsView results(@AuthenticationPrincipal AuthService.Principal principal) {
    return results.results(principal);
  }
}
