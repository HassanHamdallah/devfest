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
    record(actorId, action, target, null);
  }

  /** {@code reason} is the admin's own words for an override; never a secret, password, answer or key. */
  public void record(Long actorId, String action, String target, String reason) {
    db.update(
      "INSERT INTO audit_event(actor_id,action,target,reason) VALUES(?,?,?,?)",
      actorId,
      action,
      target,
      reason
    );
  }
}
