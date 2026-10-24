package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.instant;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the audit log's override rows for the admin board. */
@Repository
public class OverrideAuditRepository {

  private final JdbcTemplate db;

  public OverrideAuditRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** The newest {@code limit} audit rows whose action is one of {@code actions}. */
  public List<OverrideAuditRecord> recent(List<String> actions, int limit) {
    return db.query(
      """
      SELECT a.created_at, u.name, a.action, a.target, a.reason
      FROM audit_event a
      LEFT JOIN app_user u ON u.id = a.actor_id
      WHERE a.action = ANY (?)
      ORDER BY a.id DESC
      LIMIT ?""",
      ps -> {
        ps.setArray(1, ps.getConnection().createArrayOf("varchar", actions.toArray()));
        ps.setInt(2, limit);
      },
      (rs, row) ->
        new OverrideAuditRecord(
          instant(rs, "created_at"),
          rs.getString("name"),
          rs.getString("action"),
          rs.getString("target"),
          rs.getString("reason")
        )
    );
  }
}
