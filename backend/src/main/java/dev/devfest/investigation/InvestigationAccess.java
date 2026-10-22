package dev.devfest.investigation;

import static dev.devfest.api.Errors.fail;

import dev.devfest.auth.AuthService.Principal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** All investigation ownership checks live here; controllers never trust client team IDs. */
@Service
public class InvestigationAccess {

  private final JdbcTemplate db;

  public InvestigationAccess(JdbcTemplate db) {
    this.db = db;
  }

  public void admin(Principal p) {
    if (!p.role().equals("ADMIN")) throw fail(
      403,
      "Administrator access required."
    );
  }

  public void reviewer(Principal p, long room) {
    if (p.role().equals("ADMIN")) return;
    if (
      !p.role().equals("MANAGER") ||
      db.queryForObject(
        "SELECT count(*) FROM room_manager WHERE room_id=? AND user_id=?",
        Long.class,
        room,
        p.id()
      ) != 1
    ) throw fail(404, "Room not found.");
  }

  public record Team(long id, long roomId, String name, boolean archived) {}

  public Team member(Principal p, boolean lock) {
    if (!p.role().equals("MEMBER")) throw fail(
      403,
      "A team representative account is required."
    );
    var teams = db.query(
      "SELECT t.id,t.room_id,t.name,t.archived OR r.archived archived FROM team t JOIN room r ON r.id=t.room_id WHERE t.representative_id=?" +
        (lock ? " FOR UPDATE OF t,r" : ""),
      (r, n) ->
        new Team(r.getLong(1), r.getLong(2), r.getString(3), r.getBoolean(4)),
      p.id()
    );
    if (teams.isEmpty()) throw fail(
      409,
      "Ask an organizer to assign your team and room."
    );
    return teams.getFirst();
  }

  public void room(Principal p, long room) {
    if (p.role().equals("MEMBER")) {
      if (member(p, false).roomId() != room) throw fail(404, "Room not found.");
    } else reviewer(p, room);
  }

  public void activeRoom(long room) {
    if (
      db.queryForObject(
        "SELECT count(*) FROM room WHERE id=? AND NOT archived",
        Long.class,
        room
      ) != 1
    ) throw fail(409, "The room is archived or unavailable.");
  }
}
