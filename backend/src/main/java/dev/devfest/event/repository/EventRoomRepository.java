package dev.devfest.event.repository;

import static dev.devfest.event.repository.EventJdbc.nullableLong;

import dev.devfest.event.domain.RoomCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The four fixed rooms: open flag, manager assignment and room content (versioned). */
@Repository
public class EventRoomRepository {

  private static final String COLUMNS = """
    id, code, name, position, manager_id, is_open, clue_key, recovery_prompt, recovery_answer, version,
    terminal_enabled""";

  private static final String WITH_MANAGER = """
    SELECT r.id, r.code, r.name, r.manager_id, u.name AS manager_name
    FROM event_room r
    LEFT JOIN app_user u ON u.id = r.manager_id
    """;

  private final JdbcTemplate db;

  public EventRoomRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<EventRoomRecord> findAll() {
    return db.query("SELECT " + COLUMNS + " FROM event_room ORDER BY position", EventRoomRepository::map);
  }

  public Optional<EventRoomRecord> find(long roomId) {
    return single("SELECT " + COLUMNS + " FROM event_room WHERE id = ?", roomId);
  }

  /** Lock order: only after event_state and the team row. */
  public Optional<EventRoomRecord> lockForUpdate(long roomId) {
    return single("SELECT " + COLUMNS + " FROM event_room WHERE id = ? FOR UPDATE", roomId);
  }

  public Optional<EventRoomRecord> findByCode(RoomCode code) {
    return single("SELECT " + COLUMNS + " FROM event_room WHERE code = ?", code.name());
  }

  public Optional<EventRoomRecord> findByManager(long managerId) {
    return single("SELECT " + COLUMNS + " FROM event_room WHERE manager_id = ?", managerId);
  }

  public List<RoomManagerRecord> findAllWithManagers() {
    return db.query(WITH_MANAGER + "ORDER BY r.position", EventRoomRepository::mapManager);
  }

  public Optional<RoomManagerRecord> findWithManager(long roomId) {
    return db.query(WITH_MANAGER + "WHERE r.id = ?", EventRoomRepository::mapManager, roomId).stream().findFirst();
  }

  public void closeAll() {
    db.update("UPDATE event_room SET is_open = FALSE WHERE is_open");
  }

  public void setOpen(long roomId, boolean open) {
    db.update("UPDATE event_room SET is_open = ? WHERE id = ?", open, roomId);
  }

  public void setTerminalEnabled(long roomId, boolean enabled) {
    db.update("UPDATE event_room SET terminal_enabled = ? WHERE id = ?", enabled, roomId);
  }

  /** {@code managerId} null removes the manager. */
  public void assignManager(long roomId, Long managerId) {
    db.update("UPDATE event_room SET manager_id = ? WHERE id = ?", managerId, roomId);
  }

  /** Optimistic write: returns false (nothing written) when {@code expectedVersion} is stale. */
  public boolean updateContent(
    long roomId,
    long expectedVersion,
    String clueKey,
    String recoveryPrompt,
    String recoveryAnswer
  ) {
    return db.update(
      """
      UPDATE event_room
      SET clue_key = ?, recovery_prompt = ?, recovery_answer = ?, version = version + 1
      WHERE id = ? AND version = ?""",
      clueKey,
      recoveryPrompt,
      recoveryAnswer,
      roomId,
      expectedVersion
    ) == 1;
  }

  private Optional<EventRoomRecord> single(String sql, Object... args) {
    return db.query(sql, EventRoomRepository::map, args).stream().findFirst();
  }

  private static EventRoomRecord map(ResultSet rs, int row) throws SQLException {
    return new EventRoomRecord(
      rs.getLong("id"),
      RoomCode.valueOf(rs.getString("code")),
      rs.getString("name"),
      rs.getInt("position"),
      nullableLong(rs, "manager_id"),
      rs.getBoolean("is_open"),
      rs.getString("clue_key"),
      rs.getString("recovery_prompt"),
      rs.getString("recovery_answer"),
      rs.getLong("version"),
      rs.getBoolean("terminal_enabled")
    );
  }

  private static RoomManagerRecord mapManager(ResultSet rs, int row) throws SQLException {
    return new RoomManagerRecord(
      rs.getLong("id"),
      RoomCode.valueOf(rs.getString("code")),
      rs.getString("name"),
      nullableLong(rs, "manager_id"),
      rs.getString("manager_name")
    );
  }
}
