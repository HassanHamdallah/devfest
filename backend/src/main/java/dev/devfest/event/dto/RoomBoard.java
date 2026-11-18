package dev.devfest.event.dto;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.domain.RoomCode;
import java.util.List;

/** The manager's room screen. {@code scheduledGroup} is null while no round is running. */
public record RoomBoard(
  RoomDetail room,
  RoundClock clock,
  GroupCode scheduledGroup,
  RoomCounters counters,
  List<RoomTeamRow> teams,
  long revision,
  RoomArrivalView arrival,
  List<String> teamsExpectedNow
) {
  public record RoomDetail(
    long id,
    RoomCode code,
    String name,
    int position,
    boolean open,
    String clueKey,
    long version,
    boolean contentEditable,
    boolean clueKeySet,
    boolean terminalEnabled,
    RoomActivity activity,
    boolean manualScoring,
    /** The room's phone number answer, for its manager and the admin only (empty outside a points-and-phone room). */
    String phoneNumber,
    boolean phoneNumberSet
  ) {}

  public record RoomCounters(int inRoomNow, int pending, int passed, int failed) {}
}
