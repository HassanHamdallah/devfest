package dev.devfest.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Append-only record of administrative and event actions; joins the caller's transaction. */
@Component
public class AuditLog {

  private final JdbcTemplate db;

  public AuditLog(JdbcTemplate db) {
    this.db = db;
  }

  public void record(Long actorId, String action, String target) {
    db.update(
      "INSERT INTO audit_event(actor_id,action,target) VALUES(?,?,?)",
      actorId,
      action,
      target
    );
  }
}
