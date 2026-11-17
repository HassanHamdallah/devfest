package dev.devfest.event.service;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.ShipmentContent;
import dev.devfest.event.domain.EventRanking;
import dev.devfest.event.domain.FinalScoring;
import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.FinalStageResolver;
import dev.devfest.event.domain.RoomScoring;
import dev.devfest.event.dto.TeamScoreBreakdown;
import dev.devfest.event.dto.TeamScoreBreakdown.FinalScoreLine;
import dev.devfest.event.dto.TeamScoreBreakdown.RoomScoreLine;
import dev.devfest.event.repository.DeductionRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.ManualScoreRepository;
import dev.devfest.event.repository.TeamFinalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one read service that returns a team's full score breakdown (and, from it, the event ranking). Nothing is
 * stored: every call recomputes the four room scores from the rooms' own read-only score sources, the active
 * deductions and the final's recorded answers, in a fixed number of SQL statements whatever the number of teams
 * (event state, final content, rooms, teams, deductions, final progress, manual scores, plus the Linux room's 3, the
 * AI room's 1 and the contest room's 1 when those activities are on).
 *
 * <p>Room score = MAX(0, base - 2 x active hints in the room - 7.5 if an active cheating violation exists for the
 * room), rounded to 2 decimals; the final is {@link FinalScoring}. Unqualified teams simply have 0 final points.
 */
@Service
public class ScoreBreakdownService {

  /** A team's breakdown with its place; {@code rank} is null for a disqualified team. */
  public record RankedBreakdown(Integer rank, TeamScoreBreakdown breakdown) {}

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventRoomRepository rooms;
  private final EventTeamRepository teams;
  private final DeductionRepository deductions;
  private final ManualScoreRepository manualScores;
  private final TeamFinalRepository finals;
  private final FinalContentReader contentReader;
  private final SentinelScoreService aiScores;
  private final TerminalScoreService linuxScores;
  private final ContestScoreService contestScores;
  private final EventClock clock;

  public ScoreBreakdownService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventRoomRepository rooms,
    EventTeamRepository teams,
    DeductionRepository deductions,
    ManualScoreRepository manualScores,
    TeamFinalRepository finals,
    FinalContentReader contentReader,
    SentinelScoreService aiScores,
    TerminalScoreService linuxScores,
    ContestScoreService contestScores,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.rooms = rooms;
    this.teams = teams;
    this.deductions = deductions;
    this.manualScores = manualScores;
    this.finals = finals;
    this.contentReader = contentReader;
    this.aiScores = aiScores;
    this.linuxScores = linuxScores;
    this.contestScores = contestScores;
    this.clock = clock;
  }

  /** Every active team's breakdown, by team name. */
  @Transactional(readOnly = true)
  public List<TeamScoreBreakdown> all() {
    return compute();
  }

  /** The admin's endpoint: every active team's full breakdown. */
  @Transactional(readOnly = true)
  public List<TeamScoreBreakdown> all(Principal principal) {
    access.requireAdmin(principal);
    return compute();
  }

  /** One team's breakdown, or null when the team is not an active team. */
  @Transactional(readOnly = true)
  public TeamScoreBreakdown forTeam(long teamId) {
    return compute().stream().filter(b -> b.teamId() == teamId).findFirst().orElse(null);
  }

  /** The ranking: total DESC, final score DESC, earlier final finish; ties share a rank; disqualified teams last. */
  @Transactional(readOnly = true)
  public List<RankedBreakdown> ranking() {
    List<TeamScoreBreakdown> all = compute();
    Map<Long, TeamScoreBreakdown> byId = all.stream().collect(Collectors.toMap(TeamScoreBreakdown::teamId, Function.identity()));
    List<EventRanking.Entry> entries = all
      .stream()
      .map(b -> new EventRanking.Entry(b.teamId(), b.teamName(), b.total(), b.finalScore().total(), b.finalScore().finishedAt(), b.disqualified()))
      .toList();
    return EventRanking.rank(entries).stream().map(r -> new RankedBreakdown(r.rank(), byId.get(r.entry().teamId()))).toList();
  }

  // ---- computation ------------------------------------------------------------------------------

  private List<TeamScoreBreakdown> compute() {
    EventStateRecord state = states.state();
    Instant now = clock.now();
    int fullUntil = contentReader.fullUntilMinutes();
    List<EventRoomRecord> roomList = rooms.findAll();
    List<EventTeamRecord> activeTeams = teams.findActive();
    Map<Long, Map<Long, DeductionRepository.ActiveCount>> counts = new HashMap<>();
    for (DeductionRepository.ActiveCount count : deductions.activeCounts()) {
      counts.computeIfAbsent(count.roomId(), id -> new HashMap<>()).put(count.teamId(), count);
    }
    Map<Long, RoomBases> bases = new HashMap<>();
    Map<Long, Map<Long, BigDecimal>> manual = manualScores.all();
    for (EventRoomRecord room : roomList) bases.put(room.id(), bases(room, manual.getOrDefault(room.id(), Map.of())));
    Map<Long, TeamFinalRepository.Progress> progress = finals
      .progressOfActiveTeams()
      .stream()
      .collect(Collectors.toMap(TeamFinalRepository.Progress::teamId, Function.identity()));
    boolean timeUp = FinalClock.timeUp(state, now);

    return activeTeams
      .stream()
      .map(team -> breakdown(team, roomList, bases, counts, progress.get(team.id()), state, timeUp, fullUntil))
      .toList();
  }

  private TeamScoreBreakdown breakdown(
    EventTeamRecord team,
    List<EventRoomRecord> roomList,
    Map<Long, RoomBases> bases,
    Map<Long, Map<Long, DeductionRepository.ActiveCount>> counts,
    TeamFinalRepository.Progress progress,
    EventStateRecord state,
    boolean timeUp,
    int fullUntil
  ) {
    List<RoomScoreLine> lines = roomList
      .stream()
      .map(room -> {
        RoomBases source = bases.get(room.id());
        BigDecimal base = source.byTeam().getOrDefault(team.id(), BigDecimal.ZERO);
        DeductionRepository.ActiveCount count = counts.getOrDefault(room.id(), Map.of()).get(team.id());
        int hints = count == null ? 0 : count.hints();
        boolean cheating = count != null && count.cheating();
        BigDecimal score = RoomScoring.roomScore(base, hints, cheating);
        return new RoomScoreLine(
          room.id(),
          room.code(),
          room.name(),
          source.source(),
          base,
          hints,
          cheating,
          RoomScoring.round(dev.devfest.event.domain.DeductionKind.HINT.points().multiply(BigDecimal.valueOf(hints))),
          RoomScoring.round(cheating ? dev.devfest.event.domain.DeductionKind.CHEATING.points() : BigDecimal.ZERO),
          score
        );
      })
      .toList();
    BigDecimal roomsTotal = RoomScoring.round(lines.stream().map(RoomScoreLine::score).reduce(BigDecimal.ZERO, BigDecimal::add));
    FinalScoreLine finalLine = finalLine(progress, state, timeUp, fullUntil);
    return new TeamScoreBreakdown(
      team.id(),
      team.name(),
      team.groupCode(),
      team.disqualified(),
      progress == null ? 0 : progress.clues(),
      progress == null ? List.of() : progress.clueRooms(),
      lines,
      roomsTotal,
      finalLine,
      RoomScoring.round(roomsTotal.add(finalLine.total()))
    );
  }

  private static FinalScoreLine finalLine(TeamFinalRepository.Progress p, EventStateRecord state, boolean timeUp, int fullUntil) {
    int clues = p == null ? 0 : p.clues();
    boolean finalised = p != null && (p.finalised() || (timeUp && clues >= 3));
    FinalStage status = FinalStageResolver.resolve(state.finalEverOpened(), clues, finalised);
    int solved = p == null ? 0 : p.cluesSolved();
    Integer active = p == null ? null : p.activeSeconds();
    boolean suspectCorrect = p != null && p.suspectCorrect();
    FinalScoring.Score score = FinalScoring.score(new FinalScoring.Input(solved, active, suspectCorrect, fullUntil));
    return new FinalScoreLine(
      status,
      solved,
      score.clues(),
      active != null,
      active != null && suspectCorrect,
      score.suspect(),
      active,
      score.time(),
      score.total(),
      p == null ? null : p.submittedAt()
    );
  }

  /** How a room's base score is obtained, and every team's base on the 15-point scale (absent = 0). */
  private record RoomBases(String source, Map<Long, BigDecimal> byTeam) {}

  private RoomBases bases(EventRoomRecord room, Map<Long, BigDecimal> manual) {
    Map<Long, BigDecimal> byTeam = new HashMap<>();
    if (room.activity() == null) return new RoomBases("MANUAL", manual);
    return switch (room.activity()) {
      case AI_QUIZ -> {
        aiScores.roomTotals(room.id()).forEach((teamId, total) -> byTeam.put(teamId, RoomScoring.aiBase(total)));
        yield new RoomBases("AI", byTeam);
      }
      case LINUX_TERMINAL -> {
        linuxScores.roomTotals(room).forEach((teamId, total) -> byTeam.put(teamId, RoomScoring.round(BigDecimal.valueOf(total))));
        yield new RoomBases("LINUX", byTeam);
      }
      case CONTEST_RESULTS -> {
        contestScores.roomTotals(room).forEach((teamId, total) -> byTeam.put(teamId, RoomScoring.round(BigDecimal.valueOf(total))));
        yield new RoomBases("CONTEST", byTeam);
      }
    };
  }

  /** Content for tests and the placeholder alert: parsed content, or null when none is imported. */
  ShipmentContent content() {
    return contentReader.content().orElse(null);
  }
}
