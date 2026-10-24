package dev.devfest.event.seed;

import dev.devfest.event.domain.GroupCode;
import dev.devfest.event.domain.RoomCode;
import dev.devfest.event.repository.EventAccountRecord;
import dev.devfest.event.repository.EventAccountRepository;
import dev.devfest.event.repository.EventContentRepository;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.SuspectRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Practice data for local rehearsals: 4 managers, 16 teams, room content and suspects. Runs only when
 * app.event-seed-password is set, only in development mode, and only while no team exists.
 */
@Component
@Order(30)
public class EventDevelopmentSeed implements CommandLineRunner {

  private static final int TEAM_COUNT = 16;
  private static final String CORRECT_SUSPECT = "Leila Nasser";
  private static final String ENCRYPTED_MESSAGE = "WKH SUDFWLFH FOXH SRLQWV WR WKH OLEUDUB";

  private record RoomSeed(RoomCode room, String managerEmail, String managerName, String clueKey,
                          String recoveryPrompt, String recoveryAnswer) {}

  private record SuspectSeed(String name, String description) {}

  private static final List<RoomSeed> ROOMS = List.of(
    new RoomSeed(RoomCode.AI, "manager.ai@event.test", "AI manager", "WHITE VAN",
      "Practice: what is 2 + 2? Answer in words.", "FOUR"),
    new RoomSeed(RoomCode.PROBLEM_SOLVING, "manager.ps@event.test", "Problem solving manager", "RED CLAY",
      "Practice: next number in 2, 4, 8, 16?", "32"),
    new RoomSeed(RoomCode.LINUX, "manager.linux@event.test", "Linux manager", "WEST TOWER",
      "Practice: which command prints the working directory?", "pwd"),
    new RoomSeed(RoomCode.HARDWARE, "manager.hw@event.test", "Hardware manager", "+970-59-881",
      "Practice: how many bits are in a byte?", "8")
  );

  private static final List<SuspectSeed> SUSPECTS = List.of(
    new SuspectSeed("Tariq Al-Khatib", "Night-shift security guard who signed the visitor log."),
    new SuspectSeed("Nadia Murad", "Research assistant with a key to the server room."),
    new SuspectSeed("Farid Mansour", "Delivery driver seen near the loading bay."),
    new SuspectSeed(CORRECT_SUSPECT, "Librarian who catalogued the missing archive."),
    new SuspectSeed("Mazen Haddad", "IT technician who reset the door controller."),
    new SuspectSeed("Kareem Shehab", "Visiting speaker who left before the alarm.")
  );

  private final EventStateRepository state;
  private final EventAccountRepository accounts;
  private final EventTeamRepository teams;
  private final EventRoomRepository rooms;
  private final SuspectRepository suspects;
  private final EventContentRepository content;
  private final PasswordEncoder passwords;
  private final Clock clock;
  private final String password;
  private final boolean development;

  public EventDevelopmentSeed(
    EventStateRepository state,
    EventAccountRepository accounts,
    EventTeamRepository teams,
    EventRoomRepository rooms,
    SuspectRepository suspects,
    EventContentRepository content,
    PasswordEncoder passwords,
    Clock clock,
    @Value("${app.event-seed-password}") String password,
    @Value("${app.development}") boolean development
  ) {
    this.state = state;
    this.accounts = accounts;
    this.teams = teams;
    this.rooms = rooms;
    this.suspects = suspects;
    this.content = content;
    this.passwords = passwords;
    this.clock = clock;
    this.password = password;
    this.development = development;
  }

  @Override
  @Transactional
  public void run(String... args) {
    if (password.isBlank()) return;
    if (!development) throw new IllegalStateException("The event seed is restricted to development mode.");
    state.lockForUpdate();
    if (teams.count() > 0) return;
    // BCrypt is deliberately slow; every seeded account shares the same password, so hash it once.
    String passwordHash = passwords.encode(password);
    ROOMS.forEach(seed -> seedRoom(seed, passwordHash));
    seedTeams(passwordHash);
    seedFinalContent();
    state.bumpRevision();
  }

  private void seedRoom(RoomSeed seed, String passwordHash) {
    long managerId = account(seed.managerEmail(), seed.managerName(), EventAccountRepository.MANAGER, passwordHash);
    EventRoomRecord room = rooms
      .findByCode(seed.room())
      .orElseThrow(() -> new IllegalStateException("Room " + seed.room() + " is missing; check migration V5."));
    rooms.assignManager(room.id(), managerId);
    rooms.updateContent(room.id(), room.version(), seed.clueKey(), seed.recoveryPrompt(), seed.recoveryAnswer());
  }

  private void seedTeams(String passwordHash) {
    for (int n = 1; n <= TEAM_COUNT; n++) {
      String number = "%02d".formatted(n);
      long memberId = account("team" + number + "@event.test", "Team " + number, EventAccountRepository.MEMBER, passwordHash);
      teams.insert("Team " + number, GroupCode.fromIndex((n - 1) / 4), memberId, clock.instant());
    }
  }

  /** Suspects added by an organizer are kept; the practice set only fills an empty list. */
  private void seedFinalContent() {
    if (suspects.count() > 0) return;
    Long correctSuspectId = null;
    for (int position = 0; position < SUSPECTS.size(); position++) {
      SuspectSeed seed = SUSPECTS.get(position);
      long id = suspects.insert(seed.name(), seed.description(), position);
      if (seed.name().equals(CORRECT_SUSPECT)) correctSuspectId = id;
    }
    content.update(content.get().version(), ENCRYPTED_MESSAGE, correctSuspectId);
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
