package dev.devfest.investigation;

import static dev.devfest.api.Errors.fail;

import dev.devfest.auth.AuthService;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.contest.ContestService;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvestigationOrganization {

  public record Account(
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Size(max = 80) String name,
    @NotBlank @Size(min = 12, max = 72) String password,
    @NotNull @Pattern(regexp = "MEMBER|MANAGER") String role
  ) {}

  public record RoomEdit(
    @NotBlank @Size(max = 120) String name,
    boolean archived
  ) {}

  public record TeamEdit(
    @NotBlank @Size(max = 120) String name,
    @Positive long roomId,
    Long representativeId,
    boolean archived
  ) {}

  public record Assignment(
    @NotNull @Size(max = 50) List<@Positive Long> managerIds
  ) {}

  public record Room(
    long id,
    String name,
    boolean archived,
    List<Long> managerIds
  ) {}

  public record Team(
    long id,
    String name,
    long roomId,
    Long representativeId,
    boolean archived
  ) {}

  public record User(
    long id,
    String email,
    String name,
    String role,
    boolean active
  ) {}

  private final JdbcTemplate db;
  private final InvestigationAccess access;
  private final InvestigationChallenges challenges;
  private final ContestService audit;
  private final AuthService auth;
  private final PasswordEncoder passwords;

  public InvestigationOrganization(
    JdbcTemplate db,
    InvestigationAccess access,
    InvestigationChallenges challenges,
    ContestService audit,
    AuthService auth,
    PasswordEncoder passwords
  ) {
    this.db = db;
    this.access = access;
    this.challenges = challenges;
    this.audit = audit;
    this.auth = auth;
    this.passwords = passwords;
  }

  public List<Room> rooms(Principal p) {
    String where = switch (p.role()) {
      case "ADMIN" -> "";
      case "MANAGER" -> " WHERE EXISTS(SELECT 1 FROM room_manager m WHERE m.room_id=r.id AND m.user_id=?)";
      case "MEMBER" -> " WHERE EXISTS(SELECT 1 FROM team t WHERE t.room_id=r.id AND t.representative_id=?)";
      default -> throw fail(403, "Investigation access required.");
    };
    return db.query(
      "SELECT r.* FROM room r" + where + " ORDER BY r.id",
      (r, n) ->
        new Room(
          r.getLong("id"),
          r.getString("name"),
          r.getBoolean("archived"),
          p.role().equals("MEMBER")
            ? List.of()
            : db.queryForList(
                "SELECT user_id FROM room_manager WHERE room_id=? ORDER BY user_id",
                Long.class,
                r.getLong("id")
              )
        ),
      p.role().equals("ADMIN") ? new Object[] {} : new Object[] { p.id() }
    );
  }

  public List<Team> teams(Principal p) {
    String where = switch (p.role()) {
      case "ADMIN" -> "";
      case "MANAGER" -> " WHERE EXISTS(SELECT 1 FROM room_manager m WHERE m.room_id=t.room_id AND m.user_id=?)";
      case "MEMBER" -> " WHERE t.representative_id=?";
      default -> throw fail(403, "Investigation access required.");
    };
    return db.query(
      "SELECT t.* FROM team t" + where + " ORDER BY t.id",
      (r, n) ->
        new Team(
          r.getLong("id"),
          r.getString("name"),
          r.getLong("room_id"),
          r.getObject("representative_id", Long.class),
          r.getBoolean("archived")
        ),
      p.role().equals("ADMIN") ? new Object[] {} : new Object[] { p.id() }
    );
  }

  public List<User> accounts(Principal p) {
    access.admin(p);
    return db.query(
      "SELECT id,email,name,role,active FROM app_user WHERE role IN ('MEMBER','MANAGER','ADMIN') ORDER BY id",
      (r, n) ->
        new User(
          r.getLong(1),
          r.getString(2),
          r.getString(3),
          r.getString(4),
          r.getBoolean(5)
        )
    );
  }

  @Transactional
  public long account(Principal p, Account a) {
    access.admin(p);
    if (
      a.password().length() < 12 ||
      a.password().getBytes(StandardCharsets.UTF_8).length > 72
    ) throw fail(
      400,
      "Use a password of at least 12 characters and at most 72 bytes."
    );
    if (!Set.of("MEMBER", "MANAGER").contains(a.role())) throw fail(
      400,
      "Choose Member or Room manager."
    );
    long id = db.queryForObject(
      "INSERT INTO app_user(email,name,password_hash,role) VALUES(?,?,?,?) RETURNING id",
      Long.class,
      a.email().trim().toLowerCase(Locale.ROOT),
      a.name(),
      passwords.encode(a.password()),
      a.role()
    );
    audit.audit(p.id(), "INV_CREATE_" + a.role(), Long.toString(id));
    return id;
  }

  @Transactional
  public void active(Principal p, long id, boolean active) {
    access.admin(p);
    challenges.lockSettings();
    if (
      db.update(
        "UPDATE app_user SET active=? WHERE id=? AND role IN ('MEMBER','MANAGER')",
        active,
        id
      ) != 1
    ) throw fail(404, "Account not found.");
    if (!active) db.update(
      "UPDATE auth_session SET revoked=true WHERE user_id=?",
      id
    );
    audit.audit(p.id(), "INV_ACCOUNT_ACTIVE_" + active, Long.toString(id));
  }

  @Transactional
  public void password(Principal p, long id, String password) {
    access.admin(p);
    if (
      db.queryForObject(
        "SELECT count(*) FROM app_user WHERE id=?",
        Long.class,
        id
      ) != 1
    ) throw fail(404, "Account not found.");
    auth.reset(id, password);
    audit.audit(p.id(), "INV_RESET_PASSWORD", Long.toString(id));
  }

  @Transactional
  public long room(Principal p, Long id, RoomEdit r) {
    access.admin(p);
    challenges.lockSettings();
    if (id == null) id = db.queryForObject(
      "INSERT INTO room(name,archived) VALUES(?,?) RETURNING id",
      Long.class,
      r.name(),
      r.archived()
    );
    else if (
      db.update(
        "UPDATE room SET name=?,archived=? WHERE id=?",
        r.name(),
        r.archived(),
        id
      ) != 1
    ) throw fail(404, "Room not found.");
    challenges.unpublish();
    audit.audit(p.id(), "INV_ROOM_ARCHIVED_" + r.archived(), id.toString());
    return id;
  }

  @Transactional
  public long team(Principal p, Long id, TeamEdit t) {
    access.admin(p);
    challenges.lockSettings();
    boolean representativeChanged = id == null;
    if (id != null) {
      long existingId = id;
      var old = teams(p)
        .stream()
        .filter(x -> x.id() == existingId)
        .findFirst()
        .orElseThrow(() -> fail(404, "Team not found."));
      boolean reassign =
        old.roomId() != t.roomId() ||
        !Objects.equals(old.representativeId(), t.representativeId());
      representativeChanged = !Objects.equals(
        old.representativeId(),
        t.representativeId()
      );
      if (
        reassign &&
        db.queryForObject(
          "SELECT EXISTS(SELECT 1 FROM investigation_submission WHERE team_id=?) OR EXISTS(SELECT 1 FROM investigation_draft WHERE team_id=?)",
          Boolean.class,
          id,
          id
        )
      ) throw fail(
        409,
        "This team has saved work. Archive it instead of changing its enrollment."
      );
    }
    if (!t.archived()) access.activeRoom(t.roomId());
    if (
      representativeChanged &&
      t.representativeId() != null &&
      db.queryForObject(
        "SELECT count(*) FROM app_user WHERE id=? AND role='MEMBER' AND active",
        Long.class,
        t.representativeId()
      ) != 1
    ) throw fail(400, "Choose an active member as representative.");
    if (id == null) id = db.queryForObject(
      "INSERT INTO team(name,room_id,representative_id,archived) VALUES(?,?,?,?) RETURNING id",
      Long.class,
      t.name(),
      t.roomId(),
      t.representativeId(),
      t.archived()
    );
    else db.update(
      "UPDATE team SET name=?,room_id=?,representative_id=?,archived=? WHERE id=?",
      t.name(),
      t.roomId(),
      t.representativeId(),
      t.archived(),
      id
    );
    challenges.unpublish();
    audit.audit(p.id(), "INV_TEAM_ARCHIVED_" + t.archived(), id.toString());
    return id;
  }

  @Transactional
  public void managers(Principal p, long room, Assignment a) {
    access.admin(p);
    challenges.lockSettings();
    access.activeRoom(room);
    db.update("DELETE FROM room_manager WHERE room_id=?", room);
    for (long id : new HashSet<>(a.managerIds())) {
      if (
        db.queryForObject(
          "SELECT count(*) FROM app_user WHERE id=? AND role='MANAGER' AND active",
          Long.class,
          id
        ) != 1
      ) throw fail(400, "Choose active room managers.");
      db.update("INSERT INTO room_manager VALUES(?,?)", room, id);
    }
    audit.audit(p.id(), "INV_ASSIGN_MANAGERS", Long.toString(room));
  }

  public List<String> preflight(Principal p) {
    access.admin(p);
    var issues = new ArrayList<String>();
    for (var r : rooms(p))
      if (!r.archived()) {
        if (
          db.queryForObject(
            "SELECT count(*) FROM room_manager m JOIN app_user u ON u.id=m.user_id WHERE m.room_id=? AND u.active",
            Long.class,
            r.id()
          ) == 0
        ) issues.add(r.name() + ": no active manager.");
        if (
          db.queryForObject(
            "SELECT count(*) FROM investigation_challenge WHERE room_id=? AND kind='FINAL'",
            Long.class,
            r.id()
          ) == 0
        ) issues.add(r.name() + ": no final investigation.");
      }
    for (var t : teams(p))
      if (!t.archived()) {
        if (
          t.representativeId() == null ||
          db.queryForObject(
            "SELECT count(*) FROM app_user WHERE id=? AND active",
            Long.class,
            t.representativeId()
          ) == 0
        ) issues.add(t.name() + ": no active representative.");
        if (
          db.queryForObject(
            "SELECT archived FROM room WHERE id=?",
            Boolean.class,
            t.roomId()
          )
        ) issues.add(t.name() + ": assigned room is archived.");
      }
    if (
      !db.queryForObject(
        "SELECT rules_confirmed FROM investigation_settings WHERE id=1",
        Boolean.class
      )
    ) issues.add("Confirm investigation rules before accepting submissions.");
    if (rooms(p).isEmpty()) issues.add("Create at least one room.");
    if (teams(p).isEmpty()) issues.add("Create and enroll teams.");
    return issues;
  }
}
