package dev.devfest.event.dto;

import dev.devfest.event.domain.RoomActivity;
import java.math.BigDecimal;
import java.util.List;

/**
 * The AI room's content status for its manager (or ADMIN): what the imported mission holds, whether it can still
 * be replaced, and the pass score. The content itself is read with the export.
 *
 * @param passScoreLocked a team of the room was already evaluated
 * @param editable the final has not opened (content is read-only afterwards)
 * @param workStarted a team has run or saved something: the mission can no longer be replaced
 */
public record MissionEditorView(
  long roomId,
  String roomName,
  RoomActivity activity,
  int passScore,
  boolean passScoreLocked,
  boolean editable,
  boolean workStarted,
  boolean hasContent,
  List<StageSummary> stages,
  BigDecimal totalPoints
) {
  public record StageSummary(int number, String type, String mission, String title, BigDecimal maxPoints) {}
}
