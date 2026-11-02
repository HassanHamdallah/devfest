package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;
import static dev.devfest.event.service.EventText.requireMaxBytes;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.auth.SessionEnd;
import dev.devfest.event.domain.GroupCapacity;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoundRobinGroups;
import dev.devfest.event.dto.AccountView;
import dev.devfest.event.dto.CreateAccountRequest;
import dev.devfest.event.dto.CreateTeamRequest;
import dev.devfest.event.dto.EventAccountRole;
import dev.devfest.event.dto.GenerateTeamsRequest;
import dev.devfest.event.dto.Organization;
import dev.devfest.event.dto.Organization.GroupTeamCount;
import dev.devfest.event.dto.RoomManagerView;
import dev.devfest.event.dto.TeamCredential;
import dev.devfest.event.dto.TeamView;
import dev.devfest.event.dto.UpdateTeamRequest;
import dev.devfest.event.repository.EventAccountRecord;
import dev.devfest.event.repository.EventAccountRepository;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.GroupSizeRecord;
import dev.devfest.event.repository.RoomManagerRecord;
import dev.devfest.event.repository.TeamAccountRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The admin's teams and accounts screen: event accounts, teams with their groups (at most ceil(N / 4)
 * active teams per group for the planned team count N), team sessions, and room managers.
 */
@Service
public class EventOrganizationService {

  static final String GROUPS_LOCKED = "Groups are locked after round 1 starts.";
  private static final String GENERATED_EMAIL_DOMAIN = "@event.local";
  /** Generated team numbers stop here; skipping taken names never runs past it in practice. */
  private static final int MAX_GENERATED_NUMBER = 9999;

  private record GeneratedSecret(String password, String hash) {}

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventAccountRepository accounts;
  private final EventTeamRepository teams;
  private final EventRoomRepository rooms;
  private final PasswordEncoder passwords;
  private final TeamPasswordGenerator passwordGenerator;
  private final AuthService auth;
  private final AuditLog audit;
  private final EventClock clock;
  private final TransactionTemplate transactions;

  public EventOrganizationService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventAccountRepository accounts,
    EventTeamRepository teams,
    EventRoomRepository rooms,
    PasswordEncoder passwords,
    TeamPasswordGenerator passwordGenerator,
    AuthService auth,
    AuditLog audit,
    EventClock clock,
    TransactionTemplate transactions
  ) {
    this.access = access;
    this.states = states;
    this.accounts = accounts;
    this.teams = teams;
    this.rooms = rooms;
    this.passwords = passwords;
    this.passwordGenerator = passwordGenerator;
    this.auth = auth;
    this.audit = audit;
    this.clock = clock;
    this.transactions = transactions;
  }

  /** 5 SQL statements: event state, accounts, teams with presence, rooms with managers, group sizes. */
  public Organization organization(Principal principal) {
    access.requireAdmin(principal);
    return currentOrganization();
  }

  /** N may not drop below what a group already holds: every group must fit ceil(N / 4). */
  @Transactional
  public Organization setPlannedTeamCount(Principal principal, int plannedCount) {
    access.requireAdmin(principal);
    int maxPerGroup = GroupCapacity.maxPerGroup(plannedCount);
    EventStateRecord state = states.lockForUpdate();
    if (state.plannedTeamCount() == plannedCount) return currentOrganization();
    for (GroupSizeRecord group : teams.activeGroupSizes()) {
      if (group.activeTeams() > maxPerGroup) {
        throw fail(
          409,
          "Group %s has %d active teams; %d planned teams allow at most %d per group.".formatted(
            group.groupCode(),
            group.activeTeams(),
            plannedCount,
            maxPerGroup
          )
        );
      }
    }
    states.setPlannedTeamCount(plannedCount);
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_PLANNED_TEAMS", "count:" + plannedCount);
    return currentOrganization();
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
    if (!active) auth.revokeAll(accountId, SessionEnd.DEACTIVATED);
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
    EventStateRecord state = states.lockForUpdate();
    requireUniqueTeamName(name, 0);
    requireAvailableMember(request.accountId(), 0);
    requirePlannedRoom(state, 1);
    requireGroupSlot(state, request.groupCode(), 0);
    long teamId = teams.insert(name, request.groupCode(), request.accountId(), clock.now());
    return changedTeam(principal, "EVENT_TEAM_CREATED", teamId);
  }

  /**
   * The group cap applies only when the team ends up active; a reactivated team also needs room in the
   * planned count. Moving a team to another group is blocked once round 1 has started (D6). Deactivating
   * a team signs its account out.
   */
  @Transactional
  public TeamView updateTeam(Principal principal, long teamId, UpdateTeamRequest request) {
    access.requireAdmin(principal);
    String name = request.name().trim();
    EventStateRecord state = states.lockForUpdate();
    EventTeamRecord team = teams.lockForUpdate(teamId).orElseThrow(() -> fail(404, "Team not found."));
    if (team.groupCode() != request.groupCode() && groupsLocked(state)) throw fail(409, GROUPS_LOCKED);
    requireUniqueTeamName(name, teamId);
    if (team.accountId() != request.accountId()) requireAvailableMember(request.accountId(), teamId);
    if (request.active() && !team.active()) requirePlannedRoom(state, 1);
    if (request.active()) requireGroupSlot(state, request.groupCode(), teamId);
    teams.update(teamId, name, request.groupCode(), request.accountId(), request.active());
    if (team.active() && !request.active()) auth.revokeAll(team.accountId(), SessionEnd.DEACTIVATED);
    return changedTeam(principal, "EVENT_TEAM_UPDATED", teamId);
  }

  /**
   * Creates {@code count} teams with member accounts and one-time passwords, spread over the smallest
   * groups first. All or nothing. BCrypt runs before the transaction so the event_state lock is held
   * only for the inserts; the plain passwords exist only in the response.
   */
  public List<TeamCredential> generateTeams(Principal principal, GenerateTeamsRequest request) {
    access.requireAdmin(principal);
    String prefix = request.prefix().trim().replaceAll(" +", " ");
    List<GeneratedSecret> secrets = IntStream.range(0, request.count())
      .parallel()
      .mapToObj(i -> {
        String password = passwordGenerator.generate();
        return new GeneratedSecret(password, passwords.encode(password));
      })
      .toList();
    return transactions.execute(status -> insertGeneratedTeams(principal, prefix, secrets));
  }

  /** Revokes every session of the team's account; the device sees "An organizer signed your team out." */
  @Transactional
  public TeamView signOutTeam(Principal principal, long teamId) {
    access.requireAdmin(principal);
    states.lockForUpdate();
    EventTeamRecord team = teams.lockForUpdate(teamId).orElseThrow(() -> fail(404, "Team not found."));
    auth.revokeAll(team.accountId(), SessionEnd.ORGANIZER);
    return changedTeam(principal, "EVENT_TEAM_SIGNED_OUT", teamId);
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

  private List<TeamCredential> insertGeneratedTeams(Principal principal, String prefix, List<GeneratedSecret> secrets) {
    EventStateRecord state = states.lockForUpdate();
    int count = secrets.size();
    requirePlannedRoom(state, count);
    int maxPerGroup = GroupCapacity.maxPerGroup(state.plannedTeamCount());
    List<GroupCode> groups = RoundRobinGroups.assign(GroupSizeRecord.asMap(teams.activeGroupSizes()), count, maxPerGroup)
      .orElseThrow(() -> fail(409, "The groups are full (at most " + maxPerGroup + " active teams per group)."));
    String emailPrefix = prefix.replace(" ", "").toLowerCase(Locale.ROOT);
    Set<String> takenNames = teams.namesStartingWith(prefix + " ");
    Set<String> takenEmails = accounts.emailsStartingWith(emailPrefix);
    List<TeamCredential> created = new ArrayList<>(count);
    int number = 0;
    for (int i = 0; i < count; i++) {
      String name;
      String email;
      do {
        if (++number > MAX_GENERATED_NUMBER) throw fail(409, "Choose another prefix; too many names are taken.");
        String digits = "%02d".formatted(number);
        name = prefix + " " + digits;
        email = emailPrefix + digits + GENERATED_EMAIL_DOMAIN;
      } while (takenNames.contains(name) || takenEmails.contains(email));
      long accountId = accounts.insert(email, name, secrets.get(i).hash(), EventAccountRepository.MEMBER);
      teams.insert(name, groups.get(i), accountId, clock.now());
      created.add(new TeamCredential(name, groups.get(i), email, secrets.get(i).password()));
    }
    states.bumpRevision();
    audit.record(principal.id(), "EVENT_TEAMS_GENERATED", "count:" + count);
    return created;
  }

  private Organization currentOrganization() {
    EventStateRecord state = states.state();
    List<GroupSizeRecord> groups = teams.activeGroupSizes();
    return new Organization(
      accounts.findAll().stream().map(EventOrganizationService::accountView).toList(),
      teams.findAllWithAccounts(clock.now()).stream().map(EventOrganizationService::teamView).toList(),
      rooms.findAllWithManagers().stream().map(EventOrganizationService::roomManagerView).toList(),
      groups.stream().map(group -> new GroupTeamCount(group.groupCode(), group.activeTeams())).toList(),
      state.plannedTeamCount(),
      GroupCapacity.maxPerGroup(state.plannedTeamCount()),
      groupsLocked(state),
      EventViews.groupWarnings(state.plannedTeamCount(), groups)
    );
  }

  private static boolean groupsLocked(EventStateRecord state) {
    return state.currentRound() >= 1;
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

  /** {@code added} more active teams must still fit the planned team count. */
  private void requirePlannedRoom(EventStateRecord state, int added) {
    int active = teams.activeGroupSizes().stream().mapToInt(GroupSizeRecord::activeTeams).sum();
    if (active + added > state.plannedTeamCount()) {
      throw fail(409, "This would exceed the planned team count (" + state.plannedTeamCount() + ").");
    }
  }

  private void requireGroupSlot(EventStateRecord state, GroupCode group, long teamId) {
    int max = GroupCapacity.maxPerGroup(state.plannedTeamCount());
    if (teams.countOtherActiveInGroup(group, teamId) >= max) {
      throw fail(409, "Group " + group + " already has " + max + " active teams.");
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
    return teamView(teams.findWithAccount(teamId, clock.now()).orElseThrow());
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
    return new TeamView(
      team.id(),
      team.name(),
      team.groupCode(),
      team.accountId(),
      team.accountEmail(),
      team.active(),
      team.signedIn(),
      team.lastSeenAt(),
      team.disqualified(),
      team.exceptionRoom()
    );
  }

  private static RoomManagerView roomManagerView(RoomManagerRecord room) {
    return new RoomManagerView(room.id(), room.code(), room.name(), room.managerId(), room.managerName());
  }
}
