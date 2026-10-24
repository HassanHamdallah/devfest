package dev.devfest.event.service;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.FinalStage;
import dev.devfest.event.domain.FinalStageResolver;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.domain.Rotation;
import dev.devfest.event.domain.TeamRoomStatus;
import dev.devfest.event.dto.Receipt;
import dev.devfest.event.dto.TeamBoard;
import dev.devfest.event.dto.TeamBoard.ScheduledRoom;
import dev.devfest.event.dto.TeamRoomCard;
import dev.devfest.event.repository.EventBoardRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.TeamFinalRepository;
import dev.devfest.event.repository.TeamRoomProgressRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** The team home screen, built from the caller's own team only. */
@Service
public class EventTeamBoardService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventBoardRepository boards;
  private final TeamFinalRepository finals;
  private final EventClock clock;

  public EventTeamBoardService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventBoardRepository boards,
    TeamFinalRepository finals,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.boards = boards;
    this.finals = finals;
    this.clock = clock;
  }

  /** 4 SQL statements: team of the caller, event state, the team's four rooms, the team's finish record. */
  public TeamBoard board(Principal principal) {
    EventTeamRecord team = access.requireTeamOf(principal);
    EventStateRecord state = states.state();
    List<TeamRoomProgressRecord> progress = boards.teamRooms(team.id());
    boolean finished = finals.findForTeam(team.id()).isPresent();
    int cluesCollected = (int) progress.stream().filter(TeamRoomProgressRecord::clueCollected).count();
    FinalStage stage = FinalStageResolver.resolve(state.finalEverOpened(), state.finalOpen(), finished, cluesCollected);
    return new TeamBoard(
      team.name(),
      team.groupCode(),
      EventViews.roundClock(state, clock.now()),
      scheduledRoom(team, state, progress),
      progress.stream().map(room -> card(team, state, room)).toList(),
      cluesCollected,
      stage,
      state.finalOpen(),
      state.resultsPublished(),
      state.revision()
    );
  }

  private static ScheduledRoom scheduledRoom(EventTeamRecord team, EventStateRecord state, List<TeamRoomProgressRecord> rooms) {
    Optional<RoomCode> code = Rotation.roomFor(team.groupCode(), state.currentRound());
    return rooms
      .stream()
      .filter(room -> code.isPresent() && room.code() == code.get())
      .findFirst()
      .map(room -> new ScheduledRoom(room.roomId(), room.code(), room.name(), room.open()))
      .orElse(null);
  }

  private static TeamRoomCard card(EventTeamRecord team, EventStateRecord state, TeamRoomProgressRecord room) {
    int scheduledRound = Rotation.roundFor(team.groupCode().index(), room.position());
    return new TeamRoomCard(
      room.roomId(),
      room.code(),
      room.name(),
      room.position(),
      room.open(),
      scheduledRound,
      scheduledRound == state.currentRound(),
      TeamRoomStatus.of(room.judgement(), room.hasSubmission()),
      room.clueCollected(),
      room.hasSubmission()
        ? new Receipt(room.lastSubmissionId(), room.code(), room.lastReceivedAt(), room.lastClueCorrect())
        : null
    );
  }
}
