package dev.devfest.event.service;

import static dev.devfest.admin.AdminController.csvCell;
import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.ContestProblem;
import dev.devfest.event.domain.ContestScoring;
import dev.devfest.event.domain.ContestUrl;
import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.dto.ContestLinkRequest;
import dev.devfest.event.dto.ContestResultRequest;
import dev.devfest.event.dto.ContestResultsView;
import dev.devfest.event.dto.ContestResultsView.ProblemPoints;
import dev.devfest.event.dto.ContestResultsView.RoundView;
import dev.devfest.event.dto.ContestResultsView.TeamRow;
import dev.devfest.event.repository.ContestRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organizer page of the Problem Solving room (rules §20): the room's contest link, the results typed from the
 * Codeforces standings and their export, for the room's manager and the ADMIN only (a member or another room's
 * manager gets a 404, the same as for an unknown room). The site never calls Codeforces. Scores are computed on every
 * read ({@link ContestScoring}); saving a result does not change the event revision, because nothing on a team's
 * screen depends on it. The clue release is {@link ContestReleaseService}.
 */
@Service
public class ContestResultsService {

  static final String NOT_FOUND = "Contest results not found.";
  static final String TEAM_NOT_FOUND = "Team not found.";

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventTeamRepository teams;
  private final ContestRepository contest;
  private final AuditLog audit;
  private final EventClock clock;

  public ContestResultsService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventTeamRepository teams,
    ContestRepository contest,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.teams = teams;
    this.contest = contest;
    this.audit = audit;
    this.clock = clock;
  }

  /** 4 SQL statements whatever the number of teams: room, event state, link, teams with results and releases. */
  public ContestResultsView view(Principal principal, long roomId) {
    EventRoomRecord room = requireContestRoom(principal, roomId);
    return build(room, states.state());
  }

  /** Sets the contest link (https only). Content: read-only once the final opens. The revision moves, so teams re-read. */
  @Transactional
  public ContestResultsView saveLink(Principal principal, long roomId, ContestLinkRequest request) {
    EventRoomRecord room = requireContestRoom(principal, roomId);
    ContestUrl.problem(request.url()).ifPresent(problem -> {
      throw fail(400, problem);
    });
    EventStateRecord state = states.lockForUpdate();
    EventContentService.requireContentEditable(state);
    contest.saveLink(room.id(), request.url(), principal.id(), clock.now());
    states.bumpRevision();
    // The audit names the host only: a link may carry an invitation token in its query.
    audit.record(principal.id(), "EVENT_CONTEST_LINK_SET", "room:" + room.id() + " host:" + URI.create(request.url()).getHost());
    return build(room, state);
  }

  /** Removes the link (teams then see no link). Same lock as {@link #saveLink}; removing nothing changes nothing. */
  @Transactional
  public ContestResultsView clearLink(Principal principal, long roomId) {
    EventRoomRecord room = requireContestRoom(principal, roomId);
    EventStateRecord state = states.lockForUpdate();
    EventContentService.requireContentEditable(state);
    if (contest.clearLink(room.id())) {
      states.bumpRevision();
      audit.record(principal.id(), "EVENT_CONTEST_LINK_CLEARED", "room:" + room.id());
    }
    return build(room, state);
  }

  /**
   * Saves (or replaces) one team's typed result. Allowed at any time, also after the clue was released (a release is
   * never changed by it). The revision is not bumped. The team row is locked so a release running at the same moment
   * sees either the old or the new result, never half of one.
   */
  @Transactional
  public ContestResultsView saveResult(Principal principal, long roomId, long teamId, ContestResultRequest request) {
    EventRoomRecord room = requireContestRoom(principal, roomId);
    Set<ContestProblem> solved = request.problems().isEmpty()
      ? EnumSet.noneOf(ContestProblem.class)
      : EnumSet.copyOf(request.problems());
    int penalty = wholeMinutes(request.penaltyMinutes());
    teams.lockForUpdate(teamId).filter(team -> team.active() && !team.disqualified()).orElseThrow(() -> fail(404, TEAM_NOT_FOUND));
    contest.saveResult(teamId, room.id(), solved, penalty, principal.id(), clock.now());
    audit.record(
      principal.id(),
      "EVENT_CONTEST_RESULT_SAVED",
      "room:" + room.id() + " team:" + teamId + " solved:" + letters(solved, "") + " penalty:" + penalty
    );
    return build(room, states.state());
  }

  /** The table as a spreadsheet; every text cell goes through csvCell, which neutralizes spreadsheet formulas. */
  public String exportCsv(Principal principal, long roomId) {
    EventRoomRecord room = requireContestRoom(principal, roomId);
    ContestResultsView view = build(room, states.state());
    StringBuilder csv = new StringBuilder(
      "round,group,place,team,saved,solved,solvedCount,penaltyMinutes,problemPoints,bonus,total,eligible,released\r\n"
    );
    for (RoundView round : view.rounds()) {
      for (TeamRow row : round.teams()) {
        csv.append(row.round()).append(',').append(row.group()).append(',');
        csv.append(row.place() == null ? "" : row.place().toString()).append(',');
        csv.append(csvCell(row.teamName())).append(',').append(row.saved()).append(',');
        csv.append(csvCell(letters(row.solved(), " "))).append(',');
        csv.append(row.solvedCount()).append(',').append(row.penaltyMinutes()).append(',');
        csv.append(row.problemPoints()).append(',').append(row.bonus()).append(',').append(row.total()).append(',');
        csv.append(row.eligible()).append(',').append(row.released()).append("\r\n");
      }
    }
    audit.record(principal.id(), "EVENT_CONTEST_EXPORT", "room:" + room.id());
    return csv.toString();
  }

  /** The room's organizer (its manager or the admin) of a room that runs the contest activity; else the one 404. */
  EventRoomRecord requireContestRoom(Principal principal, long roomId) {
    EventRoomRecord room = access.requireOrganizerOf(principal, roomId, NOT_FOUND);
    if (room.activity() != RoomActivity.CONTEST_RESULTS) throw fail(404, NOT_FOUND);
    return room;
  }

  ContestResultsView build(EventRoomRecord room, EventStateRecord state) {
    return new ContestResultsView(
      room.id(),
      room.name(),
      contest.link(room.id()).orElse(null),
      !state.finalEverOpened(),
      room.hasClueKey(),
      ContestScoring.MAX_PROBLEM_POINTS,
      ContestScoring.MAX_TOTAL,
      ContestScoring.ELIGIBLE_SOLVED,
      Arrays.stream(ContestProblem.values()).map(problem -> new ProblemPoints(problem, problem.points())).toList(),
      ContestBoard.rounds(contest.teams(room.id()), room.position()),
      clock.now()
    );
  }

  private static int wholeMinutes(BigDecimal value) {
    if (value.signum() < 0) throw fail(400, "The penalty cannot be negative.");
    if (value.stripTrailingZeros().scale() > 0) throw fail(400, "The penalty must be a whole number of minutes.");
    if (value.compareTo(BigDecimal.valueOf(100000)) > 0) throw fail(400, "The penalty can be at most 100000 minutes.");
    return value.intValueExact();
  }

  static String letters(Collection<ContestProblem> solved, String separator) {
    return solved.stream().sorted().map(ContestProblem::name).collect(Collectors.joining(separator));
  }
}
