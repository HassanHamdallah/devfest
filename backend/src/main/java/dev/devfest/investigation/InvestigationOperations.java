package dev.devfest.investigation;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.contest.ContestService;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class InvestigationOperations {

  private final JdbcTemplate db;
  private final InvestigationAccess access;
  private final ContestService audit;
  private final Clock clock;

  public InvestigationOperations(
    JdbcTemplate db,
    InvestigationAccess access,
    ContestService audit,
    Clock clock
  ) {
    this.db = db;
    this.access = access;
    this.audit = audit;
    this.clock = clock;
  }

  @GetMapping("/api/v1/health")
  Object health() {
    try {
      db.queryForObject("SELECT 1", Integer.class);
      return Map.of("status", "UP");
    } catch (org.springframework.dao.DataAccessException e) {
      throw dev.devfest.api.Errors.fail(
        503,
        "Database unavailable. Contact the organizer."
      );
    }
  }

  @GetMapping("/api/v1/investigation/export")
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  Object export(@AuthenticationPrincipal Principal p) {
    access.admin(p);
    var out = new LinkedHashMap<String, Object>();
    out.put("exportedAt", clock.instant());
    out.put("formatVersion", 1);
    out.put(
      "accounts",
      db.queryForList(
        "SELECT id,email,name,role,active,created_at FROM app_user"
      )
    );
    for (String table : List.of(
      "investigation_settings",
      "room",
      "room_manager",
      "team",
      "investigation_challenge",
      "investigation_lifecycle",
      "investigation_draft",
      "investigation_submission",
      "investigation_score",
      "investigation_score_history",
      "audit_event"
    ))
      out.put(table, db.queryForList("SELECT * FROM " + table));
    audit.audit(p.id(), "INV_EXPORT", "1");
    return out;
  }

  @GetMapping("/api/v1/investigation/audit")
  Object events(
    @AuthenticationPrincipal Principal p,
    @RequestParam(defaultValue = "0") int page
  ) {
    access.admin(p);
    return db.queryForList(
      "SELECT id,actor_id,action,target,created_at FROM audit_event ORDER BY id DESC LIMIT 100 OFFSET ?",
      Math.max(0, page) * 100
    );
  }
}
