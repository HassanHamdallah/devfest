package dev.devfest.event.service;

import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.dto.EventHome;
import dev.devfest.event.dto.RevisionView;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import org.springframework.stereotype.Service;

/** Reads every event role uses: the polled revision and the caller's home. */
@Service
public class EventSharedService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventRoomRepository rooms;
  private final EventTeamRepository teams;

  public EventSharedService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventRoomRepository rooms,
    EventTeamRepository teams
  ) {
    this.access = access;
    this.states = states;
    this.rooms = rooms;
    this.teams = teams;
  }

  /** 1 SQL statement; polled by every open event page. */
  public RevisionView revision(Principal principal) {
    access.requireEventRole(principal);
    return new RevisionView(states.revision());
  }

  /** At most 1 SQL statement: the manager's room or the member's team. */
  public EventHome home(Principal principal) {
    access.requireEventRole(principal);
    Long roomId = EventAccessPolicy.MANAGER.equals(principal.role())
      ? rooms.findByManager(principal.id()).map(EventRoomRecord::id).orElse(null)
      : null;
    String teamName = EventAccessPolicy.MEMBER.equals(principal.role())
      ? teams.findByAccount(principal.id()).map(EventTeamRecord::name).orElse(null)
      : null;
    return new EventHome(principal.role(), roomId, teamName);
  }
}
