package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.event.dto.HealthStatus;
import dev.devfest.event.repository.EventHealthRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class EventHealthService {

  private static final HealthStatus UP = new HealthStatus("UP");

  private final EventHealthRepository health;

  public EventHealthService(EventHealthRepository health) {
    this.health = health;
  }

  public HealthStatus check() {
    try {
      health.ping();
      return UP;
    } catch (DataAccessException e) {
      throw fail(503, "Database unavailable. Contact the organizer.");
    }
  }
}
