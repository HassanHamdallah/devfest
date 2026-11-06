package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.TerminalFormCause;
import dev.devfest.event.domain.TerminalScoreItem;
import java.time.Instant;
import java.util.List;

/**
 * The organizer's dashboard of a Linux terminal room (rules §18.8): the answer keys and every active team's form with
 * its score. For the room's manager and the ADMIN only: it carries the keys. Scores are computed on read.
 * {@code keysEditable} is false once the final has opened (content lock); {@code emptyKeys} names the keys without a
 * value, whose items cannot be awarded automatically.
 */
public record TerminalDashboardView(
  long roomId,
  String roomName,
  boolean keysEditable,
  Keys keys,
  List<String> emptyKeys,
  int pointsPerItem,
  int maxPoints,
  List<TeamRow> teams,
  Instant generatedAt
) {
  /** Each holds one value per line. */
  public record Keys(String path, String username, String fileSize, String pattern) {}

  public enum Status {
    NOT_STARTED,
    SAVED,
    SUBMITTED,
  }

  /** {@code override}: null = automatic, true = awarded by an organizer, false = denied by an organizer. */
  public record ItemScore(TerminalScoreItem item, String label, boolean auto, Boolean override, boolean awarded, int points) {}

  public record TeamRow(
    long teamId,
    String teamName,
    GroupCode group,
    boolean disqualified,
    Status status,
    String path,
    String username,
    String fileSize,
    String pattern,
    String finalClue,
    boolean clueCorrect,
    List<ItemScore> items,
    int total,
    Instant savedAt,
    Instant submittedAt,
    TerminalFormCause cause
  ) {}
}
