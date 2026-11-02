package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;
import static dev.devfest.event.service.EventText.requireMaxBytes;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.dto.AccountView;
import dev.devfest.event.dto.CreateAccountRequest;
import dev.devfest.event.dto.CreateTeamRequest;
import dev.devfest.event.dto.EventAccountRole;
import dev.devfest.event.dto.Organization;
import dev.devfest.event.dto.Organization.GroupTeamCount;
import dev.devfest.event.dto.RoomManagerView;
import dev.devfest.event.dto.TeamView;
import dev.devfest.event.dto.UpdateTeamRequest;
import dev.devfest.event.repository.EventAccountRecord;
import dev.devfest.event.repository.EventAccountRepository;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.RoomManagerRecord;
import dev.devfest.event.repository.TeamAccountRecord;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admin's teams and accounts screen: event accounts, teams with their groups, and room managers. */
@Service
public class EventOrganizationService {

  private static final int TEAMS_PER_GROUP = 4;

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventAccountRepository accounts;
  private final EventTeamRepository teams;
  private final EventRoomRepository rooms;
  private final PasswordEncoder passwords;
  private final AuthService auth;
  private final AuditLog audit;
  private final EventClock clock;

  public EventOrganizationService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventAccountRepository accounts,
    EventTeamRepository teams,
    EventRoomRepository rooms,
    PasswordEncoder passwords,
    AuthService auth,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.accounts = accounts;
    this.teams = teams;
    this.rooms = rooms;
    this.passwords = passwords;
    this.auth = auth;
    this.audit = audit;
    this.clock = clock;
  }

  /** 4 SQL statements: accounts, teams, rooms with managers, group sizes. */
  public Organization organization(Principal principal) {
    access.requireAdmin(principal);
    return new Organization(
      accounts.findAll().stream().map(EventOrganizationService::accountView).toList(),
      teams.findAllWithAccounts().stream().map(EventOrganizationService::teamView).toList(),
      rooms.findAllWithManagers().stream().map(EventOrganizationService::roomManagerView).toList(),
      teams
        .activeGroupSizes()
        .stream()
        .map(group -> new GroupTeamCount(group.groupCode(), group.activeTeams()))
        .toList()
    );
  }

  @Transactional
  public AccountView createAccount(Principal principal, CreateAccountRequest request) {
    access.requireAdmin(principal);
    requireMaxBytes(request.password(), 72, "Password");
    String email = request.email().trim().toLowerCase(Locale.ROOT);
    states.lockForUpdate();
    if (accounts.emailExists(email)) throw fail(409, "An account with this email already exists.");
    long id = accounts.insert(email, request.name().trim(), passwords.encode(request.password()), request.role().name());
    return changedAccount(principal, "EVENT_ACCOUNT_CREATED", id);
  }

  /** Deactivation revokes every session, so the account is signed out at its next request. */
  @Transactional
  public AccountView setAccountActive(Principal principal, long accountId, boolean active) {
    access.requireAdmin(principal);
    states.lockForUpdate();
    EventAccountRecord account = requireAccount(accountId);
    if (account.active() == active) return accountView(account);
    accounts.setActive(accountId, active);
    if (!active) accounts.revokeSessions(accountId);
    return changedAccount(principal, active ? "EVENT_ACCOUNT_ACTIVATED" : "EVENT_ACCOUNT_DEACTIVATED", accountId);
  }

  /** AuthService.reset validates the password and revokes all sessions of the account. */
  @Transactional
  public AccountView resetPassword(Principal principal, long accountId, String password) {
    access.requireAdmin(principal);
    requireAccount(accountId);
    auth.reset(accountId, password);
    audit.record(principal.id(), "EVENT_ACCOUNT_PASSWORD_RESET", "account:" + accountId);
    return accountView(requireAccount(accountId));
  }

  @Transactional
  public TeamView createTeam(Principal principal, CreateTeamRequest request) {
    access.requireAdmin(principal);
    String name = request.name().trim();
    states.lockForUpdate();
    requireUniqueTeamName(name, 0);
    requireAvailableMember(request.accountId(), 0);
    requireGroupSlot(request.groupCode(), 0);
    long teamId = teams.insert(name, request.groupCode(), request.accountId(), clock.now());
    return changedTeam(principal, "EVENT_TEAM_CREATED", teamId);
  }

  /** The four-team group limit applies only when the team ends up active. */
  @Transactional
  public TeamView updateTeam(Principal principal, long teamId, UpdateTeamRequest request) {
    access.requireAdmin(principal);
    String name = request.name().trim();
    states.lockForUpdate();
    EventTeamRecord team = teams.lockForUpdate(teamId).orElseThrow(() -> fail(404, "Team not found."));
    requireUniqueTeamName(name, teamId);
    if (team.accountId() != request.accountId()) requireAvailableMember(request.accountId(), teamId);
    if (request.active()) requireGroupSlot(request.groupCode(), teamId);
    teams.update(teamId, name, request.groupCode(), request.accountId(), request.active());
    return changedTeam(principal, "EVENT_TEAM_UPDATED", teamId);
  }

  /** {@code managerId} null removes the manager; a manager can hold only one room (UNIQUE manager_id). */
  @Transactional
  public RoomManagerView assignManager(Principal principal, long roomId, Long managerId) {
    access.requireAdmin(principal);
    states.lockForUpdate();
    rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, "Room not found."));
    if (managerId != null) requireAvailableManager(managerId, roomId);
    rooms.assignManager(roomId, managerId);
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_ROOM_MANAGER", "room:" + roomId);
    return roomManagerView(rooms.findWithManager(roomId).orElseThrow());
  }

  private EventAccountRecord requireAccount(long accountId) {
    return accounts.find(accountId).orElseThrow(() -> fail(404, "Account not found."));
  }

  private void requireUniqueTeamName(String name, long teamId) {
    teams
      .findByName(name)
      .filter(existing -> existing.id() != teamId)
      .ifPresent(existing -> {
        throw fail(409, "A team with this name already exists.");
      });
  }

  /** {@code teamId} is the team being edited (0 when creating), which may keep its own account. */
  private void requireAvailableMember(long accountId, long teamId) {
    boolean available = accounts
      .find(accountId)
      .filter(account -> account.isMember() && account.active())
      .filter(account -> account.teamId() == null || account.teamId() == teamId)
      .isPresent();
    if (!available) throw fail(409, "That account is not an available member account.");
  }

  private void requireGroupSlot(GroupCode group, long teamId) {
    if (teams.countOtherActiveInGroup(group, teamId) >= TEAMS_PER_GROUP) {
      throw fail(409, "Group " + group + " already has " + TEAMS_PER_GROUP + " active teams.");
    }
  }

  private void requireAvailableManager(long managerId, long roomId) {
    EventAccountRecord manager = accounts
      .find(managerId)
      .filter(account -> account.isManager() && account.active())
      .orElseThrow(() -> fail(409, "That account is not an active manager account."));
    if (manager.roomId() != null && manager.roomId() != roomId) {
      throw fail(409, "That manager already manages another room.");
    }
  }

  private AccountView changedAccount(Principal principal, String action, long accountId) {
    states.bumpRevision();
    audit.record(principal.id(), action, "account:" + accountId);
    return accountView(requireAccount(accountId));
  }

  private TeamView changedTeam(Principal principal, String action, long teamId) {
    states.bumpRevision();
    audit.record(principal.id(), action, "team:" + teamId);
    return teamView(teams.findWithAccount(teamId).orElseThrow());
  }

  private static AccountView accountView(EventAccountRecord account) {
    return new AccountView(
      account.id(),
      account.email(),
      account.name(),
      EventAccountRole.valueOf(account.role()),
      account.active(),
      account.teamId(),
      account.roomId()
    );
  }

  private static TeamView teamView(TeamAccountRecord team) {
    return new TeamView(team.id(), team.name(), team.groupCode(), team.accountId(), team.accountEmail(), team.active());
  }

  private static RoomManagerView roomManagerView(RoomManagerRecord room) {
    return new RoomManagerView(room.id(), room.code(), room.name(), room.managerId(), room.managerName());
  }
}
