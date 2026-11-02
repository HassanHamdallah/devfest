package dev.devfest.event.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Rules for the text files a room's in-browser terminal starts with: path format, size limits, and which team
 * may receive them. The emulator runs in the browser; these files are the only event data it ever gets.
 */
public final class TerminalFiles {

  public static final int MAX_FILES = 20;
  public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
  public static final int MAX_ROOM_BYTES = 5 * 1024 * 1024;
  public static final int MAX_PATH_LENGTH = 120;

  private static final Pattern PATH_CHARACTERS = Pattern.compile("[A-Za-z0-9._/-]+");

  private TerminalFiles() {}

  /**
   * A path relative to the team's home directory (e.g. {@code logs/lock.log}): 1–120 characters from
   * [A-Za-z0-9._/-], no leading "/", no "." or ".." segment and no empty segment. Empty when valid.
   */
  public static Optional<String> pathProblem(String path) {
    if (path == null || path.isEmpty()) return Optional.of("Enter a file path, for example logs/lock.log.");
    if (path.length() > MAX_PATH_LENGTH) return Optional.of("The path is too long (max " + MAX_PATH_LENGTH + " characters).");
    if (!PATH_CHARACTERS.matcher(path).matches()) {
      return Optional.of("The path may only contain letters, digits, '.', '_', '-' and '/'.");
    }
    if (path.startsWith("/")) return Optional.of("The path must be relative (no leading '/').");
    for (String segment : path.split("/", -1)) {
      if (segment.equals("..")) return Optional.of("The path must not contain '..'.");
      if (segment.isEmpty() || segment.equals(".")) return Optional.of("The path has an empty or '.' part.");
    }
    return Optional.empty();
  }

  /**
   * True when a team may receive the files of {@code room}: the room's terminal is enabled, the team is not
   * disqualified, a round (1..4) runs in phase ASSIGNED, RUNNING or PAUSED, and the room is the team's assignment
   * for that round (the admin's room exception, else the rotation).
   *
   * @param phase the current round's effective phase (null in rounds 0 and 5)
   */
  public static boolean deliverableTo(
    RoomCode room,
    boolean terminalEnabled,
    GroupCode group,
    boolean disqualified,
    int currentRound,
    RoundPhase phase,
    Optional<RoomCode> exception
  ) {
    if (!terminalEnabled || disqualified || phase == null || phase == RoundPhase.TIME_UP) return false;
    return RoomAssignment.isIn(room, group, currentRound, exception);
  }
}
