package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import java.util.List;

/** The manager's room screen. {@code scheduledGroup} is null while no round is running. */
public record RoomBoard(
  RoomDetail room,
  RoundClock clock,
  GroupCode scheduledGroup,
  RoomCounters counters,
  List<RoomTeamRow> teams,
  long revision
) {
  public record RoomDetail(
    long id,
    RoomCode code,
    String name,
    int position,
    boolean open,
    String clueKey,
    String recoveryPrompt,
    String recoveryAnswer,
    long version,
    boolean contentEditable
  ) {}

  public record RoomCounters(int inRoomNow, int pending, int passed, int failed) {}
}
