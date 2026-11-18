package dev.devfest.event.service;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.FinalStageResolver;
import dev.devfest.event.domain.RoomAssignment;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.domain.TeamRoomStatus;
import dev.devfest.event.dto.TeamBoard;
import dev.devfest.event.dto.TeamBoard.ScheduledRoom;
import dev.devfest.event.dto.TeamRoomCard;
import dev.devfest.event.repository.EventBoardRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.domain.DeductionKind;
import dev.devfest.event.repository.TeamFinalRepository;
import dev.devfest.event.repository.TeamRoomExceptionRepository;
import dev.devfest.event.repository.TeamRoomProgressRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** The team home screen, built from the caller's own team only. */
@Service
public class EventTeamBoardService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventBoardRepository boards;
  private final TeamFinalRepository finals;
  private final TeamRoomExceptionRepository exceptions;
  private final EventClock clock;

  public EventTeamBoardService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventBoardRepository boards,
    TeamFinalRepository finals,
    TeamRoomExceptionRepository exceptions,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.boards = boards;
    this.finals = finals;
    this.exceptions = exceptions;
    this.clock = clock;
  }

  /**
   * 6 SQL statements whatever the number of teams: team of the caller, event state, the team's four rooms (with
   * its latest submissions and collected keys), the team's final row, all of its room exceptions and its hint count.
   * The team sees only how many of its 2 hints it used: never a cheating record, a deduction or a score.
   */
  public TeamBoard board(Principal principal) {
    EventTeamRecord team = access.requireTeamOf(principal);
    EventStateRecord state = states.state();
    List<TeamRoomProgressRecord> progress = boards.teamRooms(team.id());
    int cluesCollected = (int) progress.stream().filter(TeamRoomProgressRecord::clueCollected).count();
    Instant now = clock.now();
    TeamFinalRepository.BoardFacts facts = finals.boardFacts(team.id());
    boolean finalised = facts.finalised() || (FinalClock.timeUp(state, now) && cluesCollected >= 3);
    FinalStage stage = FinalStageResolver.resolve(state.finalEverOpened(), cluesCollected, finalised);
    Map<Integer, RoomCode> teamExceptions = exceptions.findForTeam(team.id());
    Optional<RoomCode> exception = Rotation.isRunningRound(state.currentRound())
      ? Optional.ofNullable(teamExceptions.get(state.currentRound()))
      : Optional.empty();
    Optional<RoomCode> assigned = RoomAssignment.roomFor(team.groupCode(), state.currentRound(), exception);
    return new TeamBoard(
      team.name(),
      team.groupCode(),
      EventViews.roundClock(state, now),
      scheduledRoom(assigned, exception.isPresent(), progress),
      progress.stream().map(room -> card(team, state, room, assigned)).toList(),
      cluesCollected,
      stage,
      state.finalOpen(),
      state.resultsPublished(),
      state.revision(),
      team.disqualified(),
      facts.hintsUsed(),
      DeductionKind.MAX_HINTS_PER_TEAM,
      EventViews.evidence(team, state, progress, teamExceptions, now)
    );
  }

  private static ScheduledRoom scheduledRoom(Optional<RoomCode> code, boolean exception, List<TeamRoomProgressRecord> rooms) {
    return rooms
      .stream()
      .filter(room -> code.isPresent() && room.code() == code.get())
      .findFirst()
      .map(room ->
        new ScheduledRoom(room.roomId(), room.code(), room.name(), room.open(), exception, room.terminalEnabled(), room.activity())
      )
      .orElse(null);
  }

  /**
   * {@code assigned}: this round's room (exception or rotation), which is the one marked "Now". A room with an
   * activity has no receipt: its result is not a team submission (rules §19.7); a points-and-phone room is the exception,
   * its phone number answer is an ordinary submission with an ordinary receipt (rules §21.3).
   */
  private static TeamRoomCard card(
    EventTeamRecord team,
    EventStateRecord state,
    TeamRoomProgressRecord room,
    Optional<RoomCode> assigned
  ) {
    int scheduledRound = Rotation.roundFor(team.groupCode().index(), room.position());
    return new TeamRoomCard(
      room.roomId(),
      room.code(),
      room.name(),
      room.position(),
      room.open(),
      scheduledRound,
      assigned.filter(room.code()::equals).isPresent(),
      TeamRoomStatus.of(room.judgement(), room.hasSubmission()),
      room.clueCollected(),
      room.hasSubmission() && (room.activity() == null || room.activity() == RoomActivity.PHONE_POINTS)
        ? EventViews.receipt(
          room.lastSubmissionId(),
          room.code(),
          room.lastReceivedAt(),
          room.lastClueCorrect(),
          room.lastEffectiveCorrect()
        )
        : null
    );
  }
}
