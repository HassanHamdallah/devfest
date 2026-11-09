package dev.devfest.event.seed;

import dev.devfest.event.domain.GroupCapacity;
import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.repository.EventAccountRecord;
import dev.devfest.event.repository.EventAccountRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Practice data for local rehearsals (no clue keys): 4 managers, app.event-seed-teams teams (default 16, 1..64; also the
 * planned team count), and the rooms' managers. The final's content file is imported by the admin. Runs only when app.event-seed-password is set, only in
 * development mode, and only while no team exists.
 */
@Component
@Order(30)
public class EventDevelopmentSeed implements CommandLineRunner {

  /** No clue key: managers set their room's key at runtime, so the seed leaves every key empty. */
  private record RoomSeed(RoomCode room, String managerEmail, String managerName) {}

  private static final List<RoomSeed> ROOMS = List.of(
    new RoomSeed(RoomCode.AI, "manager.ai@event.test", "AI manager"),
    new RoomSeed(RoomCode.PROBLEM_SOLVING, "manager.ps@event.test", "Problem solving manager"),
    new RoomSeed(RoomCode.LINUX, "manager.linux@event.test", "Linux manager"),
    new RoomSeed(RoomCode.HARDWARE, "manager.hw@event.test", "Hardware manager")
  );

  private final EventStateRepository state;
  private final EventAccountRepository accounts;
  private final EventTeamRepository teams;
  private final EventRoomRepository rooms;
  private final PasswordEncoder passwords;
  private final Clock clock;
  private final String password;
  private final int teamCount;
  private final boolean development;

  public EventDevelopmentSeed(
    EventStateRepository state,
    EventAccountRepository accounts,
    EventTeamRepository teams,
    EventRoomRepository rooms,
    PasswordEncoder passwords,
    Clock clock,
    @Value("${app.event-seed-password}") String password,
    @Value("${app.event-seed-teams}") int teamCount,
    @Value("${app.development}") boolean development
  ) {
    this.state = state;
    this.accounts = accounts;
    this.teams = teams;
    this.rooms = rooms;
    this.passwords = passwords;
    this.clock = clock;
    this.password = password;
    this.teamCount = teamCount;
    this.development = development;
  }

  @Override
  @Transactional
  public void run(String... args) {
    if (password.isBlank()) return;
    if (!development) throw new IllegalStateException("The event seed is restricted to development mode.");
    if (teamCount < GroupCapacity.MIN_PLANNED_TEAMS || teamCount > GroupCapacity.MAX_PLANNED_TEAMS) {
      throw new IllegalStateException("DEVFEST_EVENT_SEED_TEAMS must be between 1 and 64.");
    }
    state.lockForUpdate();
    if (teams.count() > 0) return;
    // BCrypt is deliberately slow; every seeded account shares the same password, so hash it once.
    String passwordHash = passwords.encode(password);
    ROOMS.forEach(seed -> seedRoom(seed, passwordHash));
    seedTeams(passwordHash);
    state.bumpRevision();
  }

  private void seedRoom(RoomSeed seed, String passwordHash) {
    long managerId = account(seed.managerEmail(), seed.managerName(), EventAccountRepository.MANAGER, passwordHash);
    EventRoomRecord room = rooms
      .findByCode(seed.room())
      .orElseThrow(() -> new IllegalStateException("Room " + seed.room() + " is missing; check migration V5."));
    rooms.assignManager(room.id(), managerId);
  }

  /**
   * Consecutive teams share a group in balanced blocks (16 teams: 01–04 in A, 05–08 in B, …), so group sizes
   * differ by at most one and never exceed ceil(N / 4).
   */
  private void seedTeams(String passwordHash) {
    state.setPlannedTeamCount(teamCount);
    for (int n = 1; n <= teamCount; n++) {
      String number = "%02d".formatted(n);
      long memberId = account("team" + number + "@event.test", "Team " + number, EventAccountRepository.MEMBER, passwordHash);
      teams.insert("Team " + number, GroupCode.fromIndex((n - 1) * GroupCode.values().length / teamCount), memberId, clock.instant());
    }
  }

  /** Reuses an existing account of the same role so a partly prepared database still seeds. */
  private long account(String email, String name, String role, String passwordHash) {
    return accounts
      .findByEmail(email)
      .filter(existing -> existing.role().equals(role))
      .map(EventAccountRecord::id)
      .orElseGet(() -> accounts.insert(email, name, passwordHash, role));
  }
}
