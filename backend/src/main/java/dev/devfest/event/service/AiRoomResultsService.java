package dev.devfest.event.service;

import static dev.devfest.admin.AdminController.csvCell;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.QuizScoring;
import dev.devfest.event.domain.QuizSection;
import dev.devfest.event.dto.AiRoomResultsView;
import dev.devfest.event.dto.AiRoomResultsView.QuestionStat;
import dev.devfest.event.dto.AiRoomResultsView.RepairColumn;
import dev.devfest.event.dto.AiRoomResultsView.Status;
import dev.devfest.event.dto.AiRoomResultsView.TeamResult;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventTeamRecord;
import dev.devfest.event.repository.EventTeamRepository;
import dev.devfest.event.repository.QuizAnswerRepository;
import dev.devfest.event.repository.QuizContentRepository;
import dev.devfest.event.repository.QuizEvaluationRecord;
import dev.devfest.event.repository.QuizEvaluationRepository;
import dev.devfest.event.repository.QuizMcqAnswerRecord;
import dev.devfest.event.repository.QuizQuestionRecord;
import dev.devfest.event.repository.QuizRepairAnswerRecord;
import dev.devfest.event.repository.QuizRepairRecord;
import dev.devfest.event.repository.QuizTeamProgressRecord;
import dev.devfest.event.repository.QuizTeamProgressRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The AI room results for its manager (or ADMIN): per team the MCQ score per section, each repair's score, the
 * total out of 100, its status against the pass score and whether it holds the room's clue, ranked inside the room
 * by total (desc) and then by the last answer (earlier first). The event's final ranking does not use these scores.
 * Scores are the stored, already rounded item scores.
 */
@Service
public class AiRoomResultsService {

  static final String CSV_HEADER_START = "rank,team,group,";

  private final EventAccessPolicy access;
  private final EventTeamRepository teams;
  private final QuizContentRepository content;
  private final QuizAnswerRepository answers;
  private final QuizTeamProgressRepository progress;
  private final QuizEvaluationRepository evaluations;
  private final AuditLog audit;
  private final EventClock clock;

  public AiRoomResultsService(
    EventAccessPolicy access,
    EventTeamRepository teams,
    QuizContentRepository content,
    QuizAnswerRepository answers,
    QuizTeamProgressRepository progress,
    QuizEvaluationRepository evaluations,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.teams = teams;
    this.content = content;
    this.answers = answers;
    this.progress = progress;
    this.evaluations = evaluations;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * 8 SQL statements: room (access check), questions, repairs, all MCQ answers, all repair answers, progress,
   * evaluations, teams.
   */
  public AiRoomResultsView results(Principal principal, long roomId) {
    EventRoomRecord room = access.requireManagerOf(principal, roomId);
    List<QuizQuestionRecord> questions = content.questions(roomId);
    List<QuizRepairRecord> repairs = content.repairs(roomId);
    List<QuizMcqAnswerRecord> mcq = answers.roomMcqAnswers(roomId);
    List<QuizRepairAnswerRecord> repaired = answers.roomRepairAnswers(roomId);
    List<QuizTeamProgressRecord> started = progress.forRoom(roomId);
    List<QuizEvaluationRecord> evaluated = evaluations.forRoom(roomId);
    Map<Long, EventTeamRecord> activeTeams = teams.findActive().stream().collect(Collectors.toMap(EventTeamRecord::id, t -> t));

    Set<Long> participants = new LinkedHashSet<>();
    mcq.forEach(a -> participants.add(a.teamId()));
    repaired.forEach(a -> participants.add(a.teamId()));
    started.forEach(p -> participants.add(p.teamId()));
    evaluated.forEach(e -> participants.add(e.teamId()));
    participants.retainAll(activeTeams.keySet());

    Map<Long, QuizSection> sectionOf = questions.stream().collect(Collectors.toMap(QuizQuestionRecord::id, QuizQuestionRecord::section));
    Map<Long, List<QuizMcqAnswerRecord>> mcqByTeam = mcq.stream().collect(Collectors.groupingBy(QuizMcqAnswerRecord::teamId));
    Map<Long, List<QuizRepairAnswerRecord>> repairsByTeam = repaired.stream().collect(Collectors.groupingBy(QuizRepairAnswerRecord::teamId));
    Map<Long, QuizTeamProgressRecord> progressByTeam = started.stream().collect(Collectors.toMap(QuizTeamProgressRecord::teamId, p -> p));
    Map<Long, QuizEvaluationRecord> evaluationByTeam = evaluated.stream().collect(Collectors.toMap(QuizEvaluationRecord::teamId, e -> e));

    List<TeamResult> rows = new ArrayList<>();
    for (long teamId : participants) {
      rows.add(
        teamResult(
          activeTeams.get(teamId),
          mcqByTeam.getOrDefault(teamId, List.of()),
          repairsByTeam.getOrDefault(teamId, List.of()),
          progressByTeam.get(teamId),
          evaluationByTeam.get(teamId),
          sectionOf,
          repairs,
          room.quizPassScore()
        )
      );
    }
    return new AiRoomResultsView(
      room.id(),
      room.name(),
      clock.now(),
      questions.stream().mapToInt(QuizQuestionRecord::points).sum() + repairs.stream().mapToInt(QuizRepairRecord::points).sum(),
      room.quizPassScore(),
      !evaluated.isEmpty(),
      repairColumns(repairs),
      ranked(rows),
      questionStats(questions, mcq, participants)
    );
  }

  /** The results table as CSV (team names through csvCell, which neutralizes spreadsheet formulas). */
  public String exportCsv(Principal principal, long roomId) {
    AiRoomResultsView view = results(principal, roomId);
    StringBuilder csv = new StringBuilder(CSV_HEADER_START);
    for (QuizSection section : QuizSection.values()) csv.append(section.label().toLowerCase(java.util.Locale.ROOT)).append(',');
    csv.append("mcqTotal,");
    for (RepairColumn repair : view.repairs()) csv.append("repair").append(repair.number()).append(',');
    csv.append("repairTotal,total,passScore,status,clueGranted,answered,repairsSubmitted,mcqFinishedAt,lastAnswerAt,evaluatedAt\r\n");
    for (TeamResult row : view.teams()) {
      csv.append(text(row.rank())).append(',').append(csvCell(row.teamName())).append(',').append(row.groupCode()).append(',');
      for (QuizSection section : QuizSection.values()) csv.append(row.mcqBySection().get(section).toPlainString()).append(',');
      csv.append(row.mcqTotal().toPlainString()).append(',');
      for (BigDecimal score : row.repairScores()) csv.append(score == null ? "" : score.toPlainString()).append(',');
      csv
        .append(row.repairTotal().toPlainString())
        .append(',')
        .append(row.total().toPlainString())
        .append(',')
        .append(row.passScore())
        .append(',')
        .append(row.status())
        .append(',')
        .append(row.clueGranted() ? "yes" : "no")
        .append(',')
        .append(row.answered())
        .append(',')
        .append(row.repairsSubmitted())
        .append(',')
        .append(text(row.mcqFinishedAt()))
        .append(',')
        .append(text(row.lastAnswerAt()))
        .append(',')
        .append(text(row.evaluatedAt()))
        .append("\r\n");
    }
    audit.record(principal.id(), "EVENT_QUIZ_EXPORT", "room:" + roomId);
    return csv.toString();
  }

  private static TeamResult teamResult(
    EventTeamRecord team,
    List<QuizMcqAnswerRecord> mcq,
    List<QuizRepairAnswerRecord> repaired,
    QuizTeamProgressRecord started,
    QuizEvaluationRecord evaluation,
    Map<Long, QuizSection> sectionOf,
    List<QuizRepairRecord> repairs,
    int roomPassScore
  ) {
    Map<QuizSection, List<BigDecimal>> bySection = new EnumMap<>(QuizSection.class);
    for (QuizSection section : QuizSection.values()) bySection.put(section, new ArrayList<>());
    Instant last = null;
    for (QuizMcqAnswerRecord answer : mcq) {
      QuizSection section = sectionOf.get(answer.questionId());
      if (section != null) bySection.get(section).add(answer.pointsEarned());
      last = later(last, answer.answeredAt());
    }
    Map<QuizSection, BigDecimal> mcqBySection = new EnumMap<>(QuizSection.class);
    bySection.forEach((section, scores) -> mcqBySection.put(section, QuizScoring.total(scores)));
    Map<Long, QuizRepairAnswerRecord> repairById = new HashMap<>();
    for (QuizRepairAnswerRecord answer : repaired) {
      repairById.put(answer.repairId(), answer);
      last = later(last, answer.submittedAt());
    }
    List<BigDecimal> repairScores = repairs
      .stream()
      .map(repair -> repairById.containsKey(repair.id()) ? repairById.get(repair.id()).pointsEarned() : null)
      .toList();
    BigDecimal mcqTotal = QuizScoring.total(List.copyOf(mcqBySection.values()));
    BigDecimal repairTotal = QuizScoring.total(repairScores.stream().filter(Objects::nonNull).toList());
    Status status = evaluation == null ? Status.IN_PROGRESS : evaluation.passed() ? Status.PASSED : Status.NOT_PASSED;
    return new TeamResult(
      null,
      team.id(),
      team.name(),
      team.groupCode(),
      team.disqualified(),
      mcqBySection,
      mcqTotal,
      repairScores,
      repairTotal,
      QuizScoring.total(List.of(mcqTotal, repairTotal)),
      mcq.size(),
      repaired.size(),
      started == null ? null : started.mcqFinishedAt(),
      last,
      evaluation == null ? roomPassScore : evaluation.passScore(),
      status,
      evaluation == null ? null : evaluation.evaluatedAt(),
      evaluation != null && evaluation.clueCollected()
    );
  }

  /** Total desc, then the earlier last answer, then name; disqualified teams last and unranked. */
  private static List<TeamResult> ranked(List<TeamResult> rows) {
    Comparator<TeamResult> order = Comparator
      .comparing(TeamResult::disqualified)
      .thenComparing(TeamResult::total, Comparator.reverseOrder())
      .thenComparing(TeamResult::lastAnswerAt, Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(TeamResult::teamName);
    List<TeamResult> sorted = rows.stream().sorted(order).toList();
    List<TeamResult> ranked = new ArrayList<>();
    for (TeamResult row : sorted) {
      Integer rank = row.disqualified() ? null : ranked.size() + 1;
      ranked.add(
        new TeamResult(
          rank,
          row.teamId(),
          row.teamName(),
          row.groupCode(),
          row.disqualified(),
          row.mcqBySection(),
          row.mcqTotal(),
          row.repairScores(),
          row.repairTotal(),
          row.total(),
          row.answered(),
          row.repairsSubmitted(),
          row.mcqFinishedAt(),
          row.lastAnswerAt(),
          row.passScore(),
          row.status(),
          row.evaluatedAt(),
          row.clueGranted()
        )
      );
    }
    return ranked;
  }

  private static List<RepairColumn> repairColumns(List<QuizRepairRecord> repairs) {
    List<RepairColumn> columns = new ArrayList<>();
    for (QuizRepairRecord repair : repairs) columns.add(new RepairColumn(repair.id(), columns.size() + 1, repair.title(), repair.points()));
    return columns;
  }

  /** A correct answer always earns points (every question is worth at least 1), so points > 0 means correct. */
  private static List<QuestionStat> questionStats(List<QuizQuestionRecord> questions, List<QuizMcqAnswerRecord> mcq, Set<Long> participants) {
    Map<Long, List<QuizMcqAnswerRecord>> byQuestion = mcq
      .stream()
      .filter(a -> participants.contains(a.teamId()))
      .collect(Collectors.groupingBy(QuizMcqAnswerRecord::questionId));
    return questions
      .stream()
      .map(question -> {
        List<QuizMcqAnswerRecord> given = byQuestion.getOrDefault(question.id(), List.of());
        int correct = (int) given.stream().filter(a -> a.pointsEarned().signum() > 0).count();
        int percent = participants.isEmpty() ? 0 : Math.round(100f * correct / participants.size());
        return new QuestionStat(question.id(), question.section(), question.prompt(), given.size(), correct, percent);
      })
      .toList();
  }

  private static Instant later(Instant a, Instant b) {
    return a == null || b.isAfter(a) ? b : a;
  }

  private static String text(Object value) {
    return Objects.toString(value, "");
  }
}
