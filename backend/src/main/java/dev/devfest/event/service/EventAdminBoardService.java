package dev.devfest.event.service;

import static java.util.stream.Collectors.toMap;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomActivity;
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
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.FinalTerminalBundleRepository;
import dev.devfest.event.repository.GroupSizeRecord;
import dev.devfest.event.repository.RoomArrivalRecord;
import dev.devfest.event.repository.OverrideAuditRepository;
import dev.devfest.event.repository.SentinelContentRepository;
import dev.devfest.event.repository.TerminalAnswerKeyRepository;
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
  /** Room board route prefix; the room's terminal files are managed there. */
  private static final String ROOM_BOARD_PAGE = "/event/room/";
  private static final int RECENT_OVERRIDES = 20;

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventBoardRepository boards;
  private final EventTeamRepository teams;
  private final ScoreBreakdownService scores;
  private final FinalContentReader finalContent;
  private final RoomArrivalRepository arrivals;
  private final TeamRoomExceptionRepository exceptions;
  private final OverrideAuditRepository overrideAudit;
  private final SentinelContentRepository missionContent;
  private final TerminalAnswerKeyRepository answerKeys;
  private final FinalTerminalBundleRepository finalPackage;
  private final EventClock clock;

  public EventAdminBoardService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventBoardRepository boards,
    EventTeamRepository teams,
    ScoreBreakdownService scores,
    FinalContentReader finalContent,
    RoomArrivalRepository arrivals,
    TeamRoomExceptionRepository exceptions,
    OverrideAuditRepository overrideAudit,
    SentinelContentRepository missionContent,
    TerminalAnswerKeyRepository answerKeys,
    FinalTerminalBundleRepository finalPackage,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.boards = boards;
    this.teams = teams;
    this.scores = scores;
    this.finalContent = finalContent;
    this.arrivals = arrivals;
    this.exceptions = exceptions;
    this.overrideAudit = overrideAudit;
    this.missionContent = missionContent;
    this.answerKeys = answerKeys;
    this.finalPackage = finalPackage;
    this.clock = clock;
  }

  /**
   * A fixed number of SQL statements whatever the number of teams: event state, room stats, active teams, the ranking
   * (the score breakdown reads), the final content, the final's terminal package, AI mission content stats, Linux terminal answer keys, group sizes,
   * team override flags, recent overrides, and (rounds 1..4 only) the round's arrivals and room exceptions.
   */
  public AdminBoard board(Principal principal) {
    access.requireAdmin(principal);
    EventStateRecord state = states.state();
    List<AdminRoomStatsRecord> rooms = boards.adminRooms();
    List<EventTeamRecord> activeTeams = teams.findActive();
    List<RankingRow> rows = scores.ranking().stream().map(r -> new RankingRow(r.rank(), r.breakdown())).toList();
    Optional<dev.devfest.event.domain.ShipmentContent> finalFile = finalContent.content();
    List<AdminAlert> alerts = alerts(rooms, state.plannedTeamCount(), teams.activeGroupSizes());
    EventContentGaps.find(finalFile.isPresent(), rooms).forEach(gap ->
      alerts.add(new AdminAlert(gap.code(), gap.message(), CONTENT_PAGE))
    );
    finalFile.filter(dev.devfest.event.domain.ShipmentContent::recoveryIsPlaceholder).ifPresent(file ->
      alerts.add(new AdminAlert("RECOVERY_PLACEHOLDER", "The Recovery Challenge is still the placeholder text.", CONTENT_PAGE))
    );
    // Non-blocking: the final opens without it, but teams then see "The terminal package is not ready yet".
    if (finalPackage.find().isEmpty()) {
      alerts.add(new AdminAlert("MISSING_FINAL_TERMINAL_PACKAGE", "The final terminal package is not uploaded yet.", CONTENT_PAGE));
    }
    List<AiRoomStartCheck.Blocker> blockers = AiRoomStartCheck.blockers(missionContent.stats());
    blockers.forEach(blocker -> alerts.add(new AdminAlert(blocker.code(), blocker.message(), missionEditor(blocker.roomId()), true)));
    // A form room whose answer keys are empty cannot score those items automatically (non-blocking).
    answerKeys.forFormRooms().forEach((roomId, keys) -> {
      if (keys.emptyKeys().isEmpty()) return;
      rooms.stream().filter(room -> room.id() == roomId).findFirst().ifPresent(room ->
        alerts.add(
          new AdminAlert(
            "TERMINAL_KEYS_MISSING",
            "Room " + room.name() + " has no answer key for " + String.join(", ", keys.emptyKeys()) + ": those items cannot be scored automatically.",
            ROOM_BOARD_PAGE + roomId + "/dashboard"
          )
        )
      );
    });
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
      blockers.stream().map(AiRoomStartCheck.Blocker::message).toList(),
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
      if (room.terminalEnabled() && room.terminalFiles() == 0) {
        String message = "Room " + room.name() + " has the terminal enabled but no terminal files.";
        alerts.add(new AdminAlert("TERMINAL_WITHOUT_FILES", message, ROOM_BOARD_PAGE + room.id()));
      }
      if (room.activity() == RoomActivity.CONTEST_RESULTS) {
        String page = ROOM_BOARD_PAGE + room.id() + "/contest";
        if (!room.hasContestLink()) {
          String message = "Room " + room.name() + " runs the contest but has no contest link, so teams cannot open it.";
          alerts.add(new AdminAlert("CONTEST_LINK_MISSING", message, page));
        }
        if (!room.hasClueKey()) {
          String message = "Room " + room.name() + " runs the contest but has no clue key, so its clue cannot be revealed.";
          alerts.add(new AdminAlert("CONTEST_CLUE_KEY_MISSING", message, ROOM_BOARD_PAGE + room.id()));
        }
      }
    }
    alerts.addAll(EventViews.groupWarnings(plannedTeams, groups));
    return alerts;
  }

  private static String missionEditor(long roomId) {
    return ROOM_BOARD_PAGE + roomId + "/mission";
  }

  private static TeamFlagsView teamFlags(TeamFlagsRecord flags) {
    return new TeamFlagsView(
      flags.teamId(),
      flags.teamName(),
      flags.disqualified(),
      flags.exceptionRoom(),
      flags.manualClues()
    );
  }
}
