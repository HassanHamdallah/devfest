package dev.devfest.event.service;

import static java.util.stream.Collectors.groupingBy;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.ClueCheck;
import dev.devfest.event.domain.RoomAssignment;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.JudgeStatus;
import dev.devfest.event.domain.PenaltyRules;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.dto.RoomArrivalView;
import dev.devfest.event.dto.RoomBoard;
import dev.devfest.event.dto.RoomBoard.RoomCounters;
import dev.devfest.event.dto.RoomBoard.RoomDetail;
import dev.devfest.event.dto.RoomTeamRow;
import dev.devfest.event.dto.RoomTeamRow.SubmissionDetail;
import dev.devfest.event.repository.ClueVerdictRecord;
import dev.devfest.event.repository.EventBoardRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.PenaltyRecord;
import dev.devfest.event.repository.PenaltyRepository;
import dev.devfest.event.repository.RoomArrivalRepository;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
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
  private final RoomArrivalRepository arrivals;
  private final TeamRoomExceptionRepository exceptions;
  private final EventClock clock;

  public EventRoomBoardService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventBoardRepository boards,
    PenaltyRepository penalties,
    RoomArrivalRepository arrivals,
    TeamRoomExceptionRepository exceptions,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.boards = boards;
    this.penalties = penalties;
    this.arrivals = arrivals;
    this.exceptions = exceptions;
    this.clock = clock;
  }

  /**
   * 6 SQL statements: room (access check), event state, all active teams' progress in the room, the room's
   * penalties, and (rounds 1..4 only) the room's arrival and the round's room exceptions. A team with an
   * exception counts in the room it was sent to, not in its rotation room.
   */
  public RoomBoard board(Principal principal, long roomId) {
    EventRoomRecord room = access.requireManagerOf(principal, roomId);
    EventStateRecord state = states.state();
    List<RoomTeamProgressRecord> progress = boards.roomTeams(roomId);
    Map<Long, List<PenaltyRecord>> penaltiesByTeam = penalties
      .findForRoom(roomId)
      .stream()
      .collect(groupingBy(PenaltyRecord::teamId));
    Optional<GroupCode> scheduledGroup = Rotation.groupIn(room.position(), state.currentRound());
    Map<Long, RoomCode> roundExceptions = Rotation.isRunningRound(state.currentRound())
      ? exceptions.findForRound(state.currentRound())
      : Map.of();
    // Stable sort: rows arrive ordered by team name, so scheduled teams stay alphabetical too.
    List<RoomTeamRow> rows = progress
      .stream()
      .map(team ->
        row(room, state, team, penaltiesByTeam.getOrDefault(team.teamId(), List.of()), roundExceptions.get(team.teamId()))
      )
      .sorted(SCHEDULED_FIRST)
      .toList();
    List<String> expected = rows.stream().filter(RoomTeamRow::scheduledNow).map(RoomTeamRow::teamName).sorted().toList();
    RoomArrivalView arrival = Rotation.isRunningRound(state.currentRound())
      ? EventArrivalService.view(arrivals.find(state.currentRound(), roomId))
      : null;
    return new RoomBoard(
      detail(room, state),
      EventViews.roundClock(state, clock.now()),
      scheduledGroup.orElse(null),
      counters(progress, (int) rows.stream().filter(RoomTeamRow::scheduledNow).count()),
      rows,
      state.revision(),
      arrival,
      expected
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
      !state.finalEverOpened(),
      ClueCheck.hasKey(room.clueKey())
    );
  }

  private static RoomTeamRow row(
    EventRoomRecord room,
    EventStateRecord state,
    RoomTeamProgressRecord team,
    List<PenaltyRecord> teamPenalties,
    RoomCode exception
  ) {
    int scheduledRound = Rotation.roundFor(team.groupCode().index(), room.position());
    return new RoomTeamRow(
      team.teamId(),
      team.teamName(),
      team.groupCode(),
      team.disqualified(),
      exception,
      RoomAssignment.isIn(room.code(), team.groupCode(), state.currentRound(), Optional.ofNullable(exception)),
      scheduledRound,
      submissionDetail(team.latestSubmission(), team.latestVerdict()),
      team.submissionCount(),
      EventViews.judgement(team.judgement()),
      teamPenalties.stream().map(EventViews::penalty).toList(),
      PenaltyRules.activeMinutes(teamPenalties),
      PenaltyRules.hintPenaltyMismatch(team.judgement().hints(), teamPenalties)
    );
  }

  private static SubmissionDetail submissionDetail(RoomSubmissionRecord submission, ClueVerdictRecord verdict) {
    if (submission == null) return null;
    return new SubmissionDetail(
      submission.id(),
      submission.round(),
      submission.technicalOutput(),
      submission.clueAnswer(),
      submission.clueCorrect(),
      submission.receivedAt(),
      verdict.autoCorrect(),
      verdict.override(),
      verdict.effectiveCorrect(),
      verdict.version(),
      verdict.overrideByName(),
      verdict.overrideAt()
    );
  }

  private static RoomCounters counters(List<RoomTeamProgressRecord> teams, int inRoomNow) {
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
