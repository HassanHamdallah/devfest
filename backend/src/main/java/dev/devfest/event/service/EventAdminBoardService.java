package dev.devfest.event.service;

import static java.util.stream.Collectors.toMap;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomAssignment;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.dto.AdminAlert;
import dev.devfest.event.dto.AdminBoard;
import dev.devfest.event.dto.AdminRoomCard;
import dev.devfest.event.dto.OverrideAuditView;
import dev.devfest.event.dto.TeamFlagsView;
import dev.devfest.event.dto.RankingRow;
import dev.devfest.event.dto.RotationRow;
import dev.devfest.event.repository.AdminRoomStatsRecord;
import dev.devfest.event.repository.EventBoardRepository;
import dev.devfest.event.repository.EventRankingRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.GroupSizeRecord;
import dev.devfest.event.repository.RankedTeamRecord;
import dev.devfest.event.repository.RoomArrivalRecord;
import dev.devfest.event.repository.OverrideAuditRepository;
import dev.devfest.event.repository.RoomArrivalRepository;
import dev.devfest.event.repository.TeamFlagsRecord;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/** The admin control screen: event state, room cards, rotation grid, ranking and setup alerts. */
@Service
public class EventAdminBoardService {

  private static final String CONTENT_PAGE = "/event/admin/content";
  private static final int RECENT_OVERRIDES = 20;

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventBoardRepository boards;
  private final EventTeamRepository teams;
  private final EventRankingRepository ranking;
  private final RoomArrivalRepository arrivals;
  private final TeamRoomExceptionRepository exceptions;
  private final OverrideAuditRepository overrideAudit;
  private final EventClock clock;

  public EventAdminBoardService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventBoardRepository boards,
    EventTeamRepository teams,
    EventRankingRepository ranking,
    RoomArrivalRepository arrivals,
    TeamRoomExceptionRepository exceptions,
    OverrideAuditRepository overrideAudit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.boards = boards;
    this.teams = teams;
    this.ranking = ranking;
    this.arrivals = arrivals;
    this.exceptions = exceptions;
    this.overrideAudit = overrideAudit;
    this.clock = clock;
  }

  /**
   * 10 SQL statements: event state, room stats, active teams, ranking, content status, group sizes, team
   * override flags, recent overrides, and (rounds 1..4 only) the round's arrivals and room exceptions.
   */
  public AdminBoard board(Principal principal) {
    access.requireAdmin(principal);
    EventStateRecord state = states.state();
    List<AdminRoomStatsRecord> rooms = boards.adminRooms();
    List<EventTeamRecord> activeTeams = teams.findActive();
    List<RankingRow> rows = ranking.ranking().stream().map(EventAdminBoardService::rankingRow).toList();
    List<AdminAlert> alerts = alerts(rooms, state.plannedTeamCount(), teams.activeGroupSizes());
    EventContentGaps.find(boards.contentStatus(), rooms).forEach(gap ->
      alerts.add(new AdminAlert(gap.code(), gap.message(), CONTENT_PAGE))
    );
    boolean running = Rotation.isRunningRound(state.currentRound());
    Map<Long, RoomArrivalRecord> arrived = running
      ? arrivals.findForRound(state.currentRound()).stream().collect(toMap(RoomArrivalRecord::roomId, a -> a))
      : null;
    Map<Long, RoomCode> roundExceptions = running ? exceptions.findForRound(state.currentRound()) : Map.of();
    return new AdminBoard(
      EventViews.eventState(state, clock.now()),
      roomCards(rooms, activeTeams, state.currentRound(), arrived, roundExceptions),
      rotation(),
      rows,
      alerts,
      startWarnings(rooms),
      boards.teamFlags(state.currentRound()).stream().map(EventAdminBoardService::teamFlags).toList(),
      overrideAudit
        .recent(EventOverrideService.OVERRIDE_ACTIONS, RECENT_OVERRIDES)
        .stream()
        .map(o -> new OverrideAuditView(o.at(), o.by(), o.action(), o.target(), o.reason()))
        .toList()
    );
  }

  /** {@code arrived} is null outside running rounds, where rooms carry no arrival state. */
  private static List<AdminRoomCard> roomCards(
    List<AdminRoomStatsRecord> rooms,
    List<EventTeamRecord> activeTeams,
    int round,
    Map<Long, RoomArrivalRecord> arrived,
    Map<Long, RoomCode> roundExceptions
  ) {
    // Each active team is "now" in its assigned room: the admin's exception if any, else the rotation.
    Map<RoomCode, List<String>> namesByRoom = new EnumMap<>(RoomCode.class);
    for (EventTeamRecord team : activeTeams) {
      RoomAssignment
        .roomFor(team.groupCode(), round, Optional.ofNullable(roundExceptions.get(team.id())))
        .ifPresent(room -> namesByRoom.computeIfAbsent(room, code -> new ArrayList<>()).add(team.name()));
    }
    return rooms
      .stream()
      .map(room -> {
        GroupCode groupNow = Rotation.groupIn(room.position(), round).orElse(null);
        List<String> teamsNow = namesByRoom.getOrDefault(room.code(), List.of());
        return new AdminRoomCard(
          room.id(),
          room.code(),
          room.name(),
          room.position(),
          room.open(),
          room.managerId(),
          room.managerName(),
          groupNow,
          teamsNow,
          room.passed(),
          room.failed(),
          room.pending(),
          activeTeams.size(),
          room.hasClueKey(),
          arrived == null ? null : EventArrivalService.view(Optional.ofNullable(arrived.get(room.id())))
        );
      })
      .toList();
  }

  /** Shown before the timer starts; neither blocks it (room open/close stays a separate admin action). */
  private static List<String> startWarnings(List<AdminRoomStatsRecord> rooms) {
    List<String> warnings = new ArrayList<>();
    for (AdminRoomStatsRecord room : rooms) {
      if (!room.open()) warnings.add("The " + room.name() + " room is closed.");
      if (!room.hasClueKey()) warnings.add("The " + room.name() + " room has no clue key.");
    }
    return warnings;
  }

  private static List<RotationRow> rotation() {
    return Arrays.stream(GroupCode.values())
      .map(group ->
        new RotationRow(
          group,
          IntStream.rangeClosed(Rotation.FIRST_ROUND, Rotation.LAST_ROUND)
            .mapToObj(round -> Rotation.roomFor(group, round).orElseThrow())
            .toList()
        )
      )
      .toList();
  }

  private static List<AdminAlert> alerts(
    List<AdminRoomStatsRecord> rooms,
    int plannedTeams,
    List<GroupSizeRecord> groups
  ) {
    List<AdminAlert> alerts = new ArrayList<>();
    for (AdminRoomStatsRecord room : rooms) {
      if (room.managerId() == null) {
        String message = "Room " + room.name() + " has no manager.";
        alerts.add(new AdminAlert("ROOM_WITHOUT_MANAGER", message, EventViews.TEAMS_PAGE));
      }
    }
    alerts.addAll(EventViews.groupWarnings(plannedTeams, groups));
    return alerts;
  }

  private static TeamFlagsView teamFlags(TeamFlagsRecord flags) {
    return new TeamFlagsView(
      flags.teamId(),
      flags.teamName(),
      flags.disqualified(),
      flags.exceptionRoom(),
      flags.manualClues(),
      flags.timeAdjustmentMinutes()
    );
  }

  private static RankingRow rankingRow(RankedTeamRecord row) {
    return new RankingRow(
      row.rank(),
      row.teamId(),
      row.teamName(),
      row.groupCode(),
      row.clues(),
      row.penaltyMinutes(),
      row.penaltyEntries(),
      row.finished(),
      row.finishedAt(),
      row.adjustedSeconds(),
      row.disqualified()
    );
  }
}
