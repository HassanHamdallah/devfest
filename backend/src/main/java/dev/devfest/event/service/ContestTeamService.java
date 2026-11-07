package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.domain.RoomAssignment;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.RoundPhase;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.dto.ContestTeamView;
import dev.devfest.event.repository.ContestRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * What a team sees of the Problem Solving room (rules §20.3): the contest link, only for the team assigned to the room
 * in the current round and only while that round's timer is RUNNING or PAUSED, and whether its clue was released.
 * Every other team, and a team outside its round, gets a 404. The answer holds no count, point, penalty, place or
 * eligibility; the clue itself only ever comes with the team's evidence, after the release.
 */
@Service
public class ContestTeamService {

  static final String NOT_FOUND = "Contest not found.";

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventRoomRepository rooms;
  private final TeamRoomExceptionRepository exceptions;
  private final ContestRepository contest;
  private final EventClock clock;

  public ContestTeamService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventRoomRepository rooms,
    TeamRoomExceptionRepository exceptions,
    ContestRepository contest,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.rooms = rooms;
    this.exceptions = exceptions;
    this.contest = contest;
    this.clock = clock;
  }

  /** 6 SQL statements: team, event state, room, room exception, link, release. */
  public ContestTeamView view(Principal principal, long roomId) {
    EventTeamRecord team = access.requireTeamOf(principal);
    EventStateRecord state = states.state();
    EventRoomRecord room = rooms.find(roomId).orElseThrow(() -> fail(404, NOT_FOUND));
    int round = state.currentRound();
    Optional<RoomCode> exception = Rotation.isRunningRound(round) ? exceptions.find(round, team.id()) : Optional.empty();
    if (room.activity() != RoomActivity.CONTEST_RESULTS || !RoomAssignment.isIn(room.code(), team.groupCode(), round, exception)) {
      throw fail(404, NOT_FOUND);
    }
    RoundPhase phase = state.timer().map(timer -> timer.effectivePhase(clock.now())).orElse(RoundPhase.TIME_UP);
    boolean linkVisible = !team.disqualified() && (phase == RoundPhase.RUNNING || phase == RoundPhase.PAUSED);
    return new ContestTeamView(
      room.id(),
      linkVisible ? contest.link(room.id()).orElse(null) : null,
      contest.released(team.id(), room.id())
    );
  }
}
