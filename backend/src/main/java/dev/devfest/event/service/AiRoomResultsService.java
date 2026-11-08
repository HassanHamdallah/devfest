package dev.devfest.event.service;

import static dev.devfest.admin.AdminController.csvCell;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.sentinel.SentinelContent;
import dev.devfest.event.domain.sentinel.SentinelContent.Stage;
import dev.devfest.event.dto.AiRoomResultsView;
import dev.devfest.event.dto.AiRoomResultsView.StageColumn;
import dev.devfest.event.dto.AiRoomResultsView.Status;
import dev.devfest.event.dto.AiRoomResultsView.TeamResult;
import dev.devfest.event.dto.MissionDocument;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.SentinelContentRepository;
import dev.devfest.event.repository.SentinelEvaluationRecord;
import dev.devfest.event.repository.SentinelEvaluationRepository;
import dev.devfest.event.repository.SentinelWorkRepository;
import dev.devfest.event.repository.SentinelWorkRepository.Submission;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The AI room results for its manager (or ADMIN): per team the points of each saved stage, the total out of 100, its
 * status against the pass score and whether it holds the room's clue, ranked inside the room by total (desc) and
 * then by the last saved stage (earlier first). The event's final ranking does not use these scores. Points are the
 * stored, already rounded stage points.
 */
@Service
public class AiRoomResultsService {

  static final String CSV_HEADER_START = "rank,team,group,";

  private final EventAccessPolicy access;
  private final EventTeamRepository teams;
  private final SentinelContentRepository content;
  private final SentinelWorkRepository work;
  private final SentinelEvaluationRepository evaluations;
  private final AuditLog audit;
  private final EventClock clock;
  private final ObjectMapper json;

  public AiRoomResultsService(
    EventAccessPolicy access,
    EventTeamRepository teams,
    SentinelContentRepository content,
    SentinelWorkRepository work,
    SentinelEvaluationRepository evaluations,
    AuditLog audit,
    EventClock clock,
    ObjectMapper json
  ) {
    this.access = access;
    this.teams = teams;
    this.content = content;
    this.work = work;
    this.evaluations = evaluations;
    this.audit = audit;
    this.clock = clock;
    this.json = json;
  }

  /** 6 SQL statements: room (access check), content, submissions, teams with runs, evaluations, teams. */
  public AiRoomResultsView results(Principal principal, long roomId) {
    EventRoomRecord room = access.requireManagerOf(principal, roomId);
    SentinelContent mission = content.document(roomId).map(text -> MissionDocument.load(json.readTree(text))).orElse(null);
    List<Submission> saved = work.roomSubmissions(roomId);
    List<SentinelEvaluationRecord> evaluated = evaluations.forRoom(roomId);
    Map<Long, EventTeamRecord> activeTeams = teams.findActive().stream().collect(Collectors.toMap(EventTeamRecord::id, t -> t));

    Set<Long> participants = new LinkedHashSet<>();
    saved.forEach(s -> participants.add(s.teamId()));
    participants.addAll(work.teamsWithRuns(roomId));
    evaluated.forEach(e -> participants.add(e.teamId()));
    participants.retainAll(activeTeams.keySet());

    Map<Long, List<Submission>> byTeam = saved.stream().collect(Collectors.groupingBy(Submission::teamId));
    Map<Long, SentinelEvaluationRecord> evaluationByTeam = evaluated.stream().collect(Collectors.toMap(SentinelEvaluationRecord::teamId, e -> e));
    List<TeamResult> rows = new ArrayList<>();
    for (long teamId : participants) {
      rows.add(teamResult(activeTeams.get(teamId), byTeam.getOrDefault(teamId, List.of()), evaluationByTeam.get(teamId), room.quizPassScore()));
    }
    return new AiRoomResultsView(room.id(), room.name(), clock.now(), SentinelContent.MAX_POINTS, room.quizPassScore(), !evaluated.isEmpty(), columns(mission), ranked(rows));
  }

  /** The results table as CSV (team names through csvCell, which neutralizes spreadsheet formulas). */
  public String exportCsv(Principal principal, long roomId) {
    AiRoomResultsView view = results(principal, roomId);
    StringBuilder csv = new StringBuilder(CSV_HEADER_START);
    for (StageColumn stage : view.stages()) csv.append("stage").append(stage.number()).append(',');
    csv.append("total,passScore,status,clueGranted,stagesSaved,lastSavedAt,evaluatedAt\r\n");
    for (TeamResult row : view.teams()) {
      csv.append(text(row.rank())).append(',').append(csvCell(row.teamName())).append(',').append(row.groupCode()).append(',');
      for (BigDecimal points : row.stagePoints()) csv.append(points == null ? "" : points.toPlainString()).append(',');
      csv
        .append(row.total().toPlainString())
        .append(',')
        .append(row.passScore())
        .append(',')
        .append(row.status())
        .append(',')
        .append(row.clueGranted() ? "yes" : "no")
        .append(',')
        .append(row.stagesSaved())
        .append(',')
        .append(text(row.lastSavedAt()))
        .append(',')
        .append(text(row.evaluatedAt()))
        .append("\r\n");
    }
    audit.record(principal.id(), "EVENT_MISSION_EXPORT", "room:" + roomId);
    return csv.toString();
  }

  private static List<StageColumn> columns(SentinelContent mission) {
    List<StageColumn> columns = new ArrayList<>();
    for (int number = 1; number <= SentinelContent.STAGE_COUNT; number++) {
      Stage stage = mission == null ? null : mission.stage(number);
      columns.add(new StageColumn(number, stage == null ? "" : stage.head().title(), stage == null ? null : stage.head().maxPoints()));
    }
    return columns;
  }

  private static TeamResult teamResult(EventTeamRecord team, List<Submission> saved, SentinelEvaluationRecord evaluation, int roomPassScore) {
    BigDecimal[] byStage = new BigDecimal[SentinelContent.STAGE_COUNT];
    Instant last = null;
    for (Submission submission : saved) {
      byStage[submission.stage() - 1] = submission.pointsEarned();
      if (last == null || submission.submittedAt().isAfter(last)) last = submission.submittedAt();
    }
    BigDecimal total = saved.stream().map(Submission::pointsEarned).reduce(BigDecimal.ZERO, BigDecimal::add);
    Status status = evaluation == null ? Status.IN_PROGRESS : evaluation.passed() ? Status.PASSED : Status.NOT_PASSED;
    return new TeamResult(
      null,
      team.id(),
      team.name(),
      team.groupCode(),
      team.disqualified(),
      java.util.Arrays.asList(byStage),
      total,
      saved.size(),
      last,
      evaluation == null ? roomPassScore : evaluation.passScore(),
      status,
      evaluation == null ? null : evaluation.evaluatedAt(),
      evaluation != null && evaluation.clueCollected()
    );
  }

  /** Total desc, then the earlier last saved stage, then name; disqualified teams last and unranked. */
  private static List<TeamResult> ranked(List<TeamResult> rows) {
    Comparator<TeamResult> order = Comparator
      .comparing(TeamResult::disqualified)
      .thenComparing(TeamResult::total, Comparator.reverseOrder())
      .thenComparing(TeamResult::lastSavedAt, Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(TeamResult::teamName);
    List<TeamResult> ranked = new ArrayList<>();
    for (TeamResult row : rows.stream().sorted(order).toList()) {
      Integer rank = row.disqualified() ? null : ranked.size() + 1;
      ranked.add(
        new TeamResult(
          rank,
          row.teamId(),
          row.teamName(),
          row.groupCode(),
          row.disqualified(),
          row.stagePoints(),
          row.total(),
          row.stagesSaved(),
          row.lastSavedAt(),
          row.passScore(),
          row.status(),
          row.evaluatedAt(),
          row.clueGranted()
        )
      );
    }
    return ranked;
  }

  private static String text(Object value) {
    return Objects.toString(value, "");
  }
}
