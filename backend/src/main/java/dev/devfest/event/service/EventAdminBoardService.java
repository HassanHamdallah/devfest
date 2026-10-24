package dev.devfest.event.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.dto.AdminAlert;
import dev.devfest.event.dto.AdminBoard;
import dev.devfest.event.dto.AdminRoomCard;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/** The admin control screen: event state, room cards, rotation grid, ranking and setup alerts. */
@Service
public class EventAdminBoardService {

  private static final String TEAMS_PAGE = "/event/admin/teams";
  private static final String CONTENT_PAGE = "/event/admin/content";
  private static final int TEAMS_PER_GROUP = 4;

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventBoardRepository boards;
  private final EventTeamRepository teams;
  private final EventRankingRepository ranking;
  private final EventClock clock;

  public EventAdminBoardService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventBoardRepository boards,
    EventTeamRepository teams,
    EventRankingRepository ranking,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.boards = boards;
    this.teams = teams;
    this.ranking = ranking;
    this.clock = clock;
  }

  /** 6 SQL statements: event state, room stats, active teams, ranking, content status, group sizes. */
  public AdminBoard board(Principal principal) {
    access.requireAdmin(principal);
    EventStateRecord state = states.state();
    List<AdminRoomStatsRecord> rooms = boards.adminRooms();
    List<EventTeamRecord> activeTeams = teams.findActive();
    List<RankingRow> rows = ranking.ranking().stream().map(EventAdminBoardService::rankingRow).toList();
    List<AdminAlert> alerts = alerts(rooms, teams.activeGroupSizes());
    EventContentGaps.find(boards.contentStatus(), rooms).forEach(gap ->
      alerts.add(new AdminAlert(gap.code(), gap.message(), CONTENT_PAGE))
    );
    return new AdminBoard(
      EventViews.eventState(state, clock.now()),
      roomCards(rooms, activeTeams, state.currentRound()),
      rotation(),
      rows,
      alerts
    );
  }

  private static List<AdminRoomCard> roomCards(List<AdminRoomStatsRecord> rooms, List<EventTeamRecord> activeTeams, int round) {
    Map<GroupCode, List<String>> namesByGroup = activeTeams
      .stream()
      .collect(groupingBy(EventTeamRecord::groupCode, mapping(EventTeamRecord::name, toList())));
    return rooms
      .stream()
      .map(room -> {
        GroupCode groupNow = Rotation.groupIn(room.position(), round).orElse(null);
        List<String> teamsNow = groupNow == null ? List.of() : namesByGroup.getOrDefault(groupNow, List.of());
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
          activeTeams.size()
        );
      })
      .toList();
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

  private static List<AdminAlert> alerts(List<AdminRoomStatsRecord> rooms, List<GroupSizeRecord> groups) {
    List<AdminAlert> alerts = new ArrayList<>();
    for (AdminRoomStatsRecord room : rooms) {
      if (room.managerId() == null) {
        alerts.add(new AdminAlert("ROOM_WITHOUT_MANAGER", "Room " + room.name() + " has no manager.", TEAMS_PAGE));
      }
    }
    for (GroupSizeRecord group : groups) {
      if (group.activeTeams() != TEAMS_PER_GROUP) {
        String message = "Group %s has %d active teams (needs %d).".formatted(
          group.groupCode(),
          group.activeTeams(),
          TEAMS_PER_GROUP
        );
        alerts.add(new AdminAlert("GROUP_SIZE", message, TEAMS_PAGE));
      }
    }
    return alerts;
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
      row.adjustedSeconds()
    );
  }
}
