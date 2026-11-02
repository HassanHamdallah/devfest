package dev.devfest.event.web;

import dev.devfest.event.dto.HealthStatus;
import dev.devfest.event.service.EventHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public operational endpoints (outside /api/v1/event). */
@RestController
public class EventOperationsController {

  private final EventHealthService health;

  public EventOperationsController(EventHealthService health) {
    this.health = health;
  }

  @GetMapping("/api/v1/health")
  HealthStatus health() {
    return health.check();
  }
}
