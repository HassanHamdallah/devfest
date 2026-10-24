package dev.devfest.event.service;

import static java.util.stream.Collectors.groupingBy;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.JudgeStatus;
import dev.devfest.event.domain.PenaltyRules;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.dto.RoomBoard;
import dev.devfest.event.dto.RoomBoard.RoomCounters;
import dev.devfest.event.dto.RoomBoard.RoomDetail;
import dev.devfest.event.dto.RoomTeamRow;
import dev.devfest.event.dto.RoomTeamRow.SubmissionDetail;
import dev.devfest.event.repository.EventBoardRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.PenaltyRecord;
import dev.devfest.event.repository.PenaltyRepository;
import dev.devfest.event.repository.RoomSubmissionRecord;
import dev.devfest.event.repository.RoomTeamProgressRecord;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** The manager's room screen: every active team's latest work, judgement and penalties in one room. */
@Service
public class EventRoomBoardService {

  private static final Comparator<RoomTeamRow> SCHEDULED_FIRST = Comparator.comparing(row -> !row.scheduledNow());

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventBoardRepository boards;
  private final PenaltyRepository penalties;
  private final EventClock clock;

  public EventRoomBoardService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventBoardRepository boards,
    PenaltyRepository penalties,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.boards = boards;
    this.penalties = penalties;
    this.clock = clock;
  }

  /** 4 SQL statements: room (access check), event state, all active teams' progress in the room, the room's penalties. */
  public RoomBoard board(Principal principal, long roomId) {
    EventRoomRecord room = access.requireManagerOf(principal, roomId);
    EventStateRecord state = states.state();
    List<RoomTeamProgressRecord> progress = boards.roomTeams(roomId);
    Map<Long, List<PenaltyRecord>> penaltiesByTeam = penalties
      .findForRoom(roomId)
      .stream()
      .collect(groupingBy(PenaltyRecord::teamId));
    Optional<GroupCode> scheduledGroup = Rotation.groupIn(room.position(), state.currentRound());
    // Stable sort: rows arrive ordered by team name, so scheduled teams stay alphabetical too.
    List<RoomTeamRow> rows = progress
      .stream()
      .map(team -> row(room, state, team, penaltiesByTeam.getOrDefault(team.teamId(), List.of())))
      .sorted(SCHEDULED_FIRST)
      .toList();
    return new RoomBoard(
      detail(room, state),
      EventViews.roundClock(state, clock.now()),
      scheduledGroup.orElse(null),
      counters(progress, scheduledGroup),
      rows,
      state.revision()
    );
  }

  private static RoomDetail detail(EventRoomRecord room, EventStateRecord state) {
    return new RoomDetail(
      room.id(),
      room.code(),
      room.name(),
      room.position(),
      room.open(),
      room.clueKey(),
      room.recoveryPrompt(),
      room.recoveryAnswer(),
      room.version(),
      !state.finalEverOpened()
    );
  }

  private static RoomTeamRow row(
    EventRoomRecord room,
    EventStateRecord state,
    RoomTeamProgressRecord team,
    List<PenaltyRecord> teamPenalties
  ) {
    int scheduledRound = Rotation.roundFor(team.groupCode().index(), room.position());
    return new RoomTeamRow(
      team.teamId(),
      team.teamName(),
      team.groupCode(),
      scheduledRound == state.currentRound(),
      scheduledRound,
      submissionDetail(team.latestSubmission()),
      team.submissionCount(),
      EventViews.judgement(team.judgement()),
      teamPenalties.stream().map(EventViews::penalty).toList(),
      PenaltyRules.activeMinutes(teamPenalties),
      PenaltyRules.hintPenaltyMismatch(team.judgement().hints(), teamPenalties)
    );
  }

  private static SubmissionDetail submissionDetail(RoomSubmissionRecord submission) {
    if (submission == null) return null;
    return new SubmissionDetail(
      submission.id(),
      submission.round(),
      submission.technicalOutput(),
      submission.clueAnswer(),
      submission.clueCorrect(),
      submission.receivedAt()
    );
  }

  private static RoomCounters counters(List<RoomTeamProgressRecord> teams, Optional<GroupCode> scheduledGroup) {
    int inRoomNow = (int) teams.stream().filter(team -> scheduledGroup.equals(Optional.of(team.groupCode()))).count();
    int pending = (int) teams
      .stream()
      .filter(team -> team.submissionCount() > 0 && team.judgement().status() == JudgeStatus.PENDING)
      .count();
    return new RoomCounters(inRoomNow, pending, countStatus(teams, JudgeStatus.PASS), countStatus(teams, JudgeStatus.FAIL));
  }

  private static int countStatus(List<RoomTeamProgressRecord> teams, JudgeStatus status) {
    return (int) teams.stream().filter(team -> team.judgement().status() == status).count();
  }
}
