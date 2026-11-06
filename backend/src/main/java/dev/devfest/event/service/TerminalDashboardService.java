package dev.devfest.event.service;

import static dev.devfest.admin.AdminController.csvCell;
import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.domain.TerminalAnswerKeys;
import dev.devfest.event.domain.TerminalFormScoring;
import dev.devfest.event.domain.TerminalFormValues;
import dev.devfest.event.domain.TerminalScoreItem;
import dev.devfest.event.dto.TerminalDashboardView;
import dev.devfest.event.dto.TerminalDashboardView.ItemScore;
import dev.devfest.event.dto.TerminalDashboardView.Status;
import dev.devfest.event.dto.TerminalDashboardView.TeamRow;
import dev.devfest.event.dto.TerminalKeysRequest;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.TerminalAnswerKeyRepository;
import dev.devfest.event.repository.TerminalFormOverrideRepository;
import dev.devfest.event.repository.TerminalFormRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organizer dashboard of a Linux terminal room (rules §18.8), for the room's manager and the ADMIN only (a member
 * or another room's manager gets a 404). It holds the answer keys, shows every active team's form with its score,
 * lets the organizer award or deny single items, and exports the table. Scores are never stored: each read computes
 * them from the stored forms, the current keys and the overrides ({@link TerminalFormScoring}). They stay inside this
 * room; the event's ranking does not use them. Overriding an item never touches the room's clue: the clue stays with
 * the final clue's check and the manager's ordinary clue verdict.
 */
@Service
public class TerminalDashboardService {

  static final String NOT_FOUND = "Dashboard not found.";

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventTeamRepository teams;
  private final TerminalFormRepository forms;
  private final TerminalAnswerKeyRepository keys;
  private final TerminalFormOverrideRepository overrides;
  private final AuditLog audit;
  private final EventClock clock;

  public TerminalDashboardService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventTeamRepository teams,
    TerminalFormRepository forms,
    TerminalAnswerKeyRepository keys,
    TerminalFormOverrideRepository overrides,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.teams = teams;
    this.forms = forms;
    this.keys = keys;
    this.overrides = overrides;
    this.audit = audit;
    this.clock = clock;
  }

  /** 5 SQL statements whatever the number of teams: room, event state, keys, teams with their forms, overrides. */
  public TerminalDashboardView view(Principal principal, long roomId) {
    EventRoomRecord room = requireFormRoom(principal, roomId);
    return build(room, states.state());
  }

  /** Saves the answer keys (empty is allowed). Content: read-only once the final opens. */
  @Transactional
  public TerminalDashboardView saveKeys(Principal principal, long roomId, TerminalKeysRequest request) {
    EventRoomRecord room = requireFormRoom(principal, roomId);
    TerminalAnswerKeys values = new TerminalAnswerKeys(
      request.path(),
      request.username(),
      request.fileSize(),
      request.pattern()
    );
    values.problem().ifPresent(problem -> {
      throw fail(400, problem);
    });
    EventStateRecord state = states.lockForUpdate();
    EventContentService.requireContentEditable(state);
    keys.save(room.id(), values, principal.id(), clock.now());
    states.bumpRevision();
    // Keys are answers: the audit names the room only.
    audit.record(principal.id(), "EVENT_TERMINAL_KEYS_SAVED", "room:" + room.id());
    return build(room, state);
  }

  /** Awards ({@code true}), denies ({@code false}) or clears ({@code null}) one item's points for a team. */
  @Transactional
  public TerminalDashboardView setOverride(Principal principal, long roomId, long teamId, TerminalScoreItem item, Boolean awarded) {
    EventRoomRecord room = requireFormRoom(principal, roomId);
    if (teams.find(teamId).filter(team -> team.active()).isEmpty()) throw fail(404, "Team not found.");
    if (awarded == null) {
      overrides.clear(teamId, room.id(), item);
    } else {
      overrides.set(teamId, room.id(), item, awarded, principal.id(), clock.now());
    }
    audit.record(
      principal.id(),
      "EVENT_TERMINAL_SCORE_OVERRIDE",
      "room:" + room.id() + " team:" + teamId + " item:" + item.name() + " " + (awarded == null ? "auto" : awarded ? "award" : "deny")
    );
    return build(room, states.state());
  }

  /** The table as a spreadsheet; every text cell goes through csvCell, which neutralizes spreadsheet formulas. */
  public String exportCsv(Principal principal, long roomId) {
    EventRoomRecord room = requireFormRoom(principal, roomId);
    TerminalDashboardView view = build(room, states.state());
    StringBuilder csv = new StringBuilder(
      "team,group,status,submittedAt,path,username,fileSize,pattern,finalClue,"
        + "pathPoints,usernamePoints,fileSizePoints,patternPoints,cluePoints,total,overridden\r\n"
    );
    for (TeamRow row : view.teams()) {
      csv.append(csvCell(row.teamName())).append(',').append(row.group()).append(',').append(row.status()).append(',');
      csv.append(row.submittedAt() == null ? "" : row.submittedAt().toString()).append(',');
      for (String text : List.of(row.path(), row.username(), row.fileSize(), row.pattern(), row.finalClue())) {
        csv.append(csvCell(text)).append(',');
      }
      for (ItemScore item : row.items()) csv.append(item.points()).append(',');
      csv.append(row.total()).append(',');
      csv.append(csvCell(row.items().stream().filter(item -> item.override() != null).map(item -> item.item().name()).collect(Collectors.joining(" "))));
      csv.append("\r\n");
    }
    audit.record(principal.id(), "EVENT_TERMINAL_EXPORT", "room:" + room.id());
    return csv.toString();
  }

  private EventRoomRecord requireFormRoom(Principal principal, long roomId) {
    EventRoomRecord room = access.requireOrganizerOf(principal, roomId, NOT_FOUND);
    if (room.activity() != RoomActivity.LINUX_TERMINAL) throw fail(404, NOT_FOUND);
    return room;
  }

  private TerminalDashboardView build(EventRoomRecord room, EventStateRecord state) {
    TerminalAnswerKeys answerKeys = keys.find(room.id()).map(TerminalAnswerKeyRepository.StoredKeys::keys).orElse(TerminalAnswerKeys.empty());
    Map<Long, Map<TerminalScoreItem, Boolean>> byTeam = overrides.forRoom(room.id());
    List<TeamRow> rows = forms
      .dashboardRows(room.id())
      .stream()
      .map(row -> teamRow(row, answerKeys, room.clueKey(), byTeam.getOrDefault(row.teamId(), new EnumMap<>(TerminalScoreItem.class))))
      .sorted(
        Comparator
          .comparingInt(TeamRow::total)
          .reversed()
          .thenComparing(TeamRow::submittedAt, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(TeamRow::teamName)
      )
      .toList();
    return new TerminalDashboardView(
      room.id(),
      room.name(),
      !state.finalEverOpened(),
      new TerminalDashboardView.Keys(answerKeys.path(), answerKeys.username(), answerKeys.fileSize(), answerKeys.pattern()),
      answerKeys.emptyKeys(),
      TerminalFormScoring.POINTS_PER_ITEM,
      TerminalFormScoring.MAX_POINTS,
      rows,
      clock.now()
    );
  }

  private static TeamRow teamRow(
    TerminalFormRepository.DashboardRow row,
    TerminalAnswerKeys answerKeys,
    String clueKey,
    Map<TerminalScoreItem, Boolean> teamOverrides
  ) {
    var form = row.form();
    TerminalFormValues values = form == null ? TerminalFormValues.empty() : form.values();
    TerminalFormScoring.Score score = form == null
      ? TerminalFormScoring.notStarted(teamOverrides)
      : TerminalFormScoring.score(answerKeys, values, clueKey, teamOverrides);
    Status status = form == null ? Status.NOT_STARTED : form.submitted() ? Status.SUBMITTED : Status.SAVED;
    List<ItemScore> items = score
      .items()
      .stream()
      .map(item -> new ItemScore(item.item(), item.item().label(), item.auto(), item.override(), item.awarded(), item.points()))
      .toList();
    return new TeamRow(
      row.teamId(),
      row.teamName(),
      row.group(),
      row.disqualified(),
      status,
      values.path(),
      values.username(),
      values.fileSize(),
      values.pattern(),
      values.finalClue(),
      score.clueCorrect(),
      items,
      score.total(),
      form == null ? null : form.savedAt(),
      form == null ? null : form.submittedAt(),
      form == null ? null : form.cause()
    );
  }
}
