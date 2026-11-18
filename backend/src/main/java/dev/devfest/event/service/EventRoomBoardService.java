package dev.devfest.event.service;

import static java.util.stream.Collectors.groupingBy;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.ClueCheck;
import dev.devfest.event.domain.RoomAssignment;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.JudgeStatus;
import dev.devfest.event.domain.DeductionKind;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.dto.RoomArrivalView;
import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.dto.PointsView;
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
import dev.devfest.event.repository.DeductionRecord;
import dev.devfest.event.repository.DeductionRepository;
import dev.devfest.event.repository.ManualScoreRepository;
import dev.devfest.event.repository.PointsEntryRecord;
import dev.devfest.event.repository.RoomPointsRepository;
import dev.devfest.event.repository.RoomArrivalRepository;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
import dev.devfest.event.repository.RoomSubmissionRecord;
import dev.devfest.event.repository.RoomTeamProgressRecord;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** The manager's room screen: every active team's latest work, judgement and deductions in one room. */
@Service
public class EventRoomBoardService {

  private static final Comparator<RoomTeamRow> SCHEDULED_FIRST = Comparator.comparing(row -> !row.scheduledNow());

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventBoardRepository boards;
  private final DeductionRepository deductions;
  private final ManualScoreRepository manualScores;
  private final RoomPointsRepository roomPoints;
  private final RoomArrivalRepository arrivals;
  private final TeamRoomExceptionRepository exceptions;
  private final EventClock clock;

  public EventRoomBoardService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventBoardRepository boards,
    DeductionRepository deductions,
    ManualScoreRepository manualScores,
    RoomPointsRepository roomPoints,
    RoomArrivalRepository arrivals,
    TeamRoomExceptionRepository exceptions,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.boards = boards;
    this.deductions = deductions;
    this.manualScores = manualScores;
    this.roomPoints = roomPoints;
    this.arrivals = arrivals;
    this.exceptions = exceptions;
    this.clock = clock;
  }

  /**
   * 6 to 8 SQL statements whatever the number of teams: room (access check), event state, all active teams' progress in
   * the room, the room's deductions, every team's active hint count, the typed scores (rooms without an automatic score
   * only) and (rounds 1..4 only) the room's arrival and the round's room exceptions. A team with an
   * exception counts in the room it was sent to, not in its rotation room.
   */
  public RoomBoard board(Principal principal, long roomId) {
    EventRoomRecord room = access.requireManagerOf(principal, roomId);
    EventStateRecord state = states.state();
    List<RoomTeamProgressRecord> progress = boards.roomTeams(roomId);
    Map<Long, List<DeductionRecord>> deductionsByTeam = deductions
      .findForRoom(roomId)
      .stream()
      .collect(groupingBy(DeductionRecord::teamId));
    Map<Long, Integer> hintsUsed = deductions.activeHintsByTeam();
    Map<Long, BigDecimal> manual = room.activity() == null ? manualScores.forRoom(roomId) : Map.of();
    Map<Long, List<PointsEntryRecord>> ledger = room.activity() == RoomActivity.PHONE_POINTS ? roomPoints.forRoom(roomId) : Map.of();
    Optional<GroupCode> scheduledGroup = Rotation.groupIn(room.position(), state.currentRound());
    Map<Long, RoomCode> roundExceptions = Rotation.isRunningRound(state.currentRound())
      ? exceptions.findForRound(state.currentRound())
      : Map.of();
    // Stable sort: rows arrive ordered by team name, so scheduled teams stay alphabetical too.
    List<RoomTeamRow> rows = progress
      .stream()
      .map(team ->
        row(
          room,
          state,
          team,
          deductionsByTeam.getOrDefault(team.teamId(), List.of()),
          hintsUsed.getOrDefault(team.teamId(), 0),
          manual.get(team.teamId()),
          room.activity() == RoomActivity.PHONE_POINTS ? RoomPointsService.view(team.teamId(), roomId, ledger.getOrDefault(team.teamId(), List.of())) : null,
          roundExceptions.get(team.teamId())
        )
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
      room.version(),
      !state.finalEverOpened(),
      ClueCheck.hasKey(room.clueKey()),
      room.terminalEnabled(),
      room.activity(),
      room.activity() == null,
      room.activity() == RoomActivity.PHONE_POINTS ? room.phoneNumber() : "",
      room.activity() == RoomActivity.PHONE_POINTS && room.hasPhoneNumber()
    );
  }

  private static RoomTeamRow row(
    EventRoomRecord room,
    EventStateRecord state,
    RoomTeamProgressRecord team,
    List<DeductionRecord> teamDeductions,
    int hintsUsed,
    BigDecimal manualScore,
    PointsView points,
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
      teamDeductions.stream().map(EventViews::deduction).toList(),
      (int) teamDeductions.stream().filter(d -> !d.cancelled() && d.kind() == DeductionKind.HINT).count(),
      hintsUsed,
      DeductionKind.MAX_HINTS_PER_TEAM,
      teamDeductions.stream().anyMatch(d -> !d.cancelled() && d.kind() == DeductionKind.CHEATING),
      manualScore,
      points == null ? null : points.total(),
      points == null ? null : points.score(),
      points == null ? null : points.undoDelta()
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
