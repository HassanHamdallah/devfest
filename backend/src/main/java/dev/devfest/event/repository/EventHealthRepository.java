package dev.devfest.event.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Database liveness probe. */
@Repository
public class EventHealthRepository {

  private final JdbcTemplate db;

  public EventHealthRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** Throws a DataAccessException when the database cannot answer. */
  public void ping() {
    db.queryForObject("SELECT 1", Integer.class);
  }
}
