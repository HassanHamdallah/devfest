package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.ContestProblem;
import dev.devfest.event.domain.ContestScoring;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.dto.ContestResultsView;
import dev.devfest.event.repository.ContestRepository;
import dev.devfest.event.repository.ContestTeamRecord;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.RoomSubmissionRecord;
import dev.devfest.event.service.TeamWriteGate.TeamWrite;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The clue release of the Problem Solving room (rules §20.6), by the room's manager or the ADMIN. A release records the
 * team's result through the ordinary room-submission path as CORRECT (technical output: a summary the server makes;
 * clue answer: a fixed text), which collects the room's clue as a ROOM clue and finishes the room for the team, and
 * keeps one {@code contest_release} row that says so. The manager's ordinary clue verdict still works on that
 * submission, and key changes never re-check it. Editing results afterwards changes no clue.
 *
 * <p>Every release runs in one transaction under the usual lock order (event_state, then the teams in id order, then
 * the room), so a single release, a release of a whole round and a repeat of either cannot interleave: the second
 * finds the release and does nothing (the primary key of contest_release backs this up). One revision bump per call
 * that released anything, broadcast after the commit.
 */
@Service
public class ContestReleaseService {

  /** The stored clue answer of a release: fixed text, never the clue and never a score. */
  static final String RESULT_TEXT = "Contest results released";
  static final String NO_CLUE_KEY = "Set the clue key for this room before revealing its clue.";
  private static final String REQUEST_HASH = AuthService.hash("contest-release");

  private final ContestResultsService results;
  private final EventStateRepository states;
  private final EventTeamRepository teams;
  private final EventRoomRepository rooms;
  private final ContestRepository contest;
  private final EventSubmissionService submissions;
  private final AuditLog audit;
  private final EventClock clock;

  public ContestReleaseService(
    ContestResultsService results,
    EventStateRepository states,
    EventTeamRepository teams,
    EventRoomRepository rooms,
    ContestRepository contest,
    EventSubmissionService submissions,
    AuditLog audit,
    EventClock clock
  ) {
    this.results = results;
    this.states = states;
    this.teams = teams;
    this.rooms = rooms;
    this.contest = contest;
    this.submissions = submissions;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Reveals one team's clue. A team that was released already is returned as it is (no second submission, clue or
   * revision bump, whatever changed since); a team without enough solved problems, or a room without a clue key, is
   * refused with a 409.
   */
  @Transactional
  public ContestResultsView reveal(Principal principal, long roomId, long teamId) {
    results.requireContestRoom(principal, roomId);
    EventStateRecord state = states.lockForUpdate();
    EventTeamRecord team = teams.lockForUpdate(teamId).filter(EventTeamRecord::active).orElseThrow(() -> fail(404, ContestResultsService.TEAM_NOT_FOUND));
    EventRoomRecord locked = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, ContestResultsService.NOT_FOUND));
    if (contest.released(teamId, roomId)) return results.build(locked, state);
    if (team.disqualified()) throw fail(409, "This team is disqualified; its clue cannot be revealed.");
    requireClueKey(locked);
    List<ContestTeamRecord> all = contest.teams(roomId);
    ContestTeamRecord row = all.stream().filter(candidate -> candidate.teamId() == teamId).findFirst().orElseThrow(() -> fail(404, ContestResultsService.TEAM_NOT_FOUND));
    ContestScoring.Score score = ContestBoard.scores(all, locked.position()).get(teamId);
    if (score == null) throw fail(409, "This team has no saved results yet, so it is not eligible for the clue.");
    if (!score.eligible()) {
      throw fail(409, "This team solved %d of the problems; at least %d are needed for the clue.".formatted(score.solvedCount(), ContestScoring.ELIGIBLE_SOLVED));
    }
    release(principal.id(), state, team, locked, row, score, clock.now());
    states.bumpRevision();
    return results.build(locked, state);
  }

  /**
   * Reveals the clue of every eligible team of one round that does not have it yet. Nothing eligible is not an error
   * (nothing happens); a room without a clue key is. The eligible teams are chosen again after their locks are held,
   * so a result saved a moment earlier counts.
   */
  @Transactional
  public ContestResultsView revealRound(Principal principal, long roomId, int round) {
    EventRoomRecord room = results.requireContestRoom(principal, roomId);
    if (round < Rotation.FIRST_ROUND || round > Rotation.LAST_ROUND) throw fail(400, "The round must be 1 to 4.");
    EventStateRecord state = states.lockForUpdate();
    requireClueKey(room);
    List<Long> candidates = releasable(contest.teams(roomId), room.position(), round).stream().map(ContestTeamRecord::teamId).toList();
    if (candidates.isEmpty()) return results.build(room, state);
    // Lock order event_state → teams (by id) → room, as on every team write.
    Map<Long, EventTeamRecord> locked = teams.lockForUpdate(candidates).stream().collect(java.util.stream.Collectors.toMap(EventTeamRecord::id, team -> team));
    EventRoomRecord lockedRoom = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, ContestResultsService.NOT_FOUND));
    requireClueKey(lockedRoom);
    List<ContestTeamRecord> all = contest.teams(roomId);
    Map<Long, ContestScoring.Score> scores = ContestBoard.scores(all, lockedRoom.position());
    Instant now = clock.now();
    int released = 0;
    for (ContestTeamRecord row : releasable(all, lockedRoom.position(), round)) {
      EventTeamRecord team = locked.get(row.teamId());
      if (team == null) continue;
      release(principal.id(), state, team, lockedRoom, row, scores.get(row.teamId()), now);
      released++;
    }
    if (released > 0) states.bumpRevision();
    return results.build(lockedRoom, state);
  }

  /** The round's teams with a saved, eligible result and no release yet. */
  private static List<ContestTeamRecord> releasable(List<ContestTeamRecord> all, int roomPosition, int round) {
    Map<Long, ContestScoring.Score> scores = ContestBoard.scores(all, roomPosition);
    return all
      .stream()
      .filter(row -> ContestBoard.roundOf(row, roomPosition) == round && !row.released())
      .filter(row -> scores.containsKey(row.teamId()) && scores.get(row.teamId()).eligible())
      .toList();
  }

  private static void requireClueKey(EventRoomRecord room) {
    if (!room.hasClueKey()) throw fail(409, NO_CLUE_KEY);
  }

  /** The caller holds the event_state, team and room locks and bumps the revision. */
  private void release(long actorId, EventStateRecord state, EventTeamRecord team, EventRoomRecord room, ContestTeamRecord row, ContestScoring.Score score, Instant now) {
    int round = Rotation.roundFor(team.groupCode().index(), room.position());
    // A random key: the release has no request key of its own, and a fixed one could collide with a manual entry.
    RoomSubmissionRecord submission = submissions.recordActivityResultForRound(
      new TeamWrite(team, state, REQUEST_HASH),
      room,
      round,
      UUID.randomUUID(),
      summary(row, score),
      RESULT_TEXT,
      true
    );
    contest.insertRelease(team.id(), room.id(), round, submission.id(), actorId, now);
    audit.record(actorId, "EVENT_CONTEST_CLUE_RELEASED", "room:" + room.id() + " team:" + team.id() + " round:" + round);
  }

  /** What the manager sees as the submission's technical output: the facts behind the release, made by the server. */
  static String summary(ContestTeamRecord row, ContestScoring.Score score) {
    String solved = ContestResultsService.letters(row.solved(), ", ");
    return "Contest results · solved %d/%d (%s) · problem points %d/%d · penalty %d min · place %d · bonus %d · total %d/%d".formatted(
      score.solvedCount(),
      ContestProblem.values().length,
      solved.isEmpty() ? "none" : solved,
      score.problemPoints(),
      ContestScoring.MAX_PROBLEM_POINTS,
      row.penaltyMinutes(),
      score.place(),
      score.bonus(),
      score.total(),
      ContestScoring.MAX_TOTAL
    );
  }
}
