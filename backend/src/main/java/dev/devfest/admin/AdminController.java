package dev.devfest.admin;

import static dev.devfest.api.Errors.fail;

import dev.devfest.auth.*;
import dev.devfest.contest.*;
import dev.devfest.submission.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

  private final JdbcTemplate db;
  private final PasswordEncoder passwords;
  private final AuthService auth;
  private final ContestService contest;
  private final SubmissionService submissions;

  public AdminController(
    JdbcTemplate db,
    PasswordEncoder passwords,
    AuthService auth,
    ContestService contest,
    SubmissionService submissions
  ) {
    this.db = db;
    this.passwords = passwords;
    this.auth = auth;
    this.contest = contest;
    this.submissions = submissions;
  }

  public record Participant(
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Size(max = 80) String name,
    @NotBlank @Size(min = 12, max = 72) String password
  ) {}

  public record User(long id, String email, String name, boolean active) {}

  public record Active(boolean active) {}

  public record Password(@NotBlank @Size(min = 12, max = 72) String password) {}

  public record Settings(
    @NotBlank @Size(max = 120) String title,
    @NotNull Instant startAt,
    @NotNull Instant endAt
  ) {}

  @GetMapping("/participants")
  List<User> users(@RequestParam(defaultValue = "0") int page) {
    return db.query(
      "SELECT id,email,name,active FROM app_user WHERE role='PARTICIPANT' ORDER BY id LIMIT 50 OFFSET ?",
      (r, n) ->
        new User(r.getLong(1), r.getString(2), r.getString(3), r.getBoolean(4)),
      Math.max(0, page) * 50
    );
  }

  @PostMapping("/participants")
  @Transactional
  Map<String, Long> create(
    @Valid @RequestBody Participant r,
    @AuthenticationPrincipal AuthService.Principal p
  ) {
    if (
      r.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
    ) throw fail(400, "Password is too long.");
    long id = db.queryForObject(
      "INSERT INTO app_user(email,name,password_hash,role) VALUES(?,?,?,'PARTICIPANT') RETURNING id",
      Long.class,
      r.email().trim().toLowerCase(Locale.ROOT),
      r.name(),
      passwords.encode(r.password())
    );
    contest.audit(p.id(), "CREATE_PARTICIPANT", Long.toString(id));
    return Map.of("id", id);
  }

  @PatchMapping("/participants/{id}")
  @Transactional
  Map<String, Boolean> active(
    @PathVariable long id,
    @RequestBody Active r,
    @AuthenticationPrincipal AuthService.Principal p
  ) {
    if (
      db.update(
        "UPDATE app_user SET active=? WHERE id=? AND role='PARTICIPANT'",
        r.active(),
        id
      ) != 1
    ) throw fail(404, "Participant not found.");
    if (!r.active()) db.update(
      "UPDATE auth_session SET revoked=true WHERE user_id=?",
      id
    );
    contest.audit(p.id(), "SET_ACTIVE", Long.toString(id));
    return Map.of("ok", true);
  }

  @PostMapping("/participants/{id}/password")
  @Transactional
  Map<String, Boolean> reset(
    @PathVariable long id,
    @Valid @RequestBody Password r,
    @AuthenticationPrincipal AuthService.Principal p
  ) {
    if (
      db.queryForObject(
        "SELECT count(*) FROM app_user WHERE id=? AND role='PARTICIPANT'",
        Long.class,
        id
      ) != 1
    ) throw fail(404, "Participant not found.");
    auth.reset(id, r.password());
    contest.audit(p.id(), "RESET_PASSWORD", Long.toString(id));
    return Map.of("ok", true);
  }

  @PutMapping("/contest")
  @Transactional
  Map<String, Boolean> settings(
    @Valid @RequestBody Settings r,
    @AuthenticationPrincipal AuthService.Principal p
  ) {
    if (!r.startAt().isBefore(r.endAt())) throw fail(
      400,
      "End must follow start."
    );
    db.update(
      "UPDATE contest SET title=?,start_at=?,end_at=? WHERE id=1",
      r.title(),
      Timestamp.from(r.startAt()),
      Timestamp.from(r.endAt())
    );
    contest.audit(p.id(), "EDIT_CONTEST", "1");
    return Map.of("ok", true);
  }

  @GetMapping("/problems/{id}")
  ProblemData problem(@PathVariable long id) {
    return contest.problem(id, contest.version(id));
  }

  @PostMapping("/problems")
  Map<String, Long> createProblem(
    @Valid @RequestBody ProblemData r,
    @AuthenticationPrincipal AuthService.Principal p
  ) {
    return Map.of("id", contest.saveProblem(null, r, p.id()));
  }

  @PutMapping("/problems/{id}")
  Map<String, Long> updateProblem(
    @PathVariable long id,
    @Valid @RequestBody ProblemData r,
    @AuthenticationPrincipal AuthService.Principal p
  ) {
    return Map.of("id", contest.saveProblem(id, r, p.id()));
  }

  @GetMapping("/overview")
  Map<String, Object> overview() {
    return Map.of(
      "participants",
      db.queryForObject(
        "SELECT count(*) FROM app_user WHERE role='PARTICIPANT'",
        Long.class
      ),
      "evaluations",
      db.queryForList("SELECT state,count(*) FROM evaluation GROUP BY state"),
      "oldestQueuedSeconds",
      db.queryForObject(
        "SELECT coalesce(extract(epoch FROM now()-min(s.received_at)),0) FROM submission s JOIN evaluation e ON e.submission_id=s.id WHERE e.state='QUEUED'",
        Double.class
      )
    );
  }

  @PostMapping("/submissions/{id}/rejudge")
  Map<String, Boolean> rejudge(
    @PathVariable UUID id,
    @AuthenticationPrincipal AuthService.Principal p
  ) {
    submissions.rejudge(id, p.id());
    return Map.of("ok", true);
  }

  @GetMapping(value = "/results.csv", produces = "text/csv")
  String csv() {
    var out = new StringBuilder("rank,name,solved,lastAccepted\r\n");
    for (var s : contest.scores())
      out
        .append(s.rank())
        .append(',')
        .append(csvCell(s.name()))
        .append(',')
        .append(s.solved())
        .append(',')
        .append(s.lastAccepted() == null ? "" : s.lastAccepted())
        .append("\r\n");
    return out.toString();
  }

  static String csvCell(String value) {
    String safe =
      !value.isEmpty() && "=+-@\t\r\n".indexOf(value.charAt(0)) >= 0
        ? "'" + value
        : value;
    return "\"" + safe.replace("\"", "\"\"") + "\"";
  }
}
