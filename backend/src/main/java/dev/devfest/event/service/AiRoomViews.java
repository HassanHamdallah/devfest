package dev.devfest.event.service;

import dev.devfest.event.domain.QuizSection;
import dev.devfest.event.domain.QuizShuffle;
import dev.devfest.event.domain.QuizShuffle.Kind;
import dev.devfest.event.domain.QuizWindow;
import dev.devfest.event.domain.QuizWindow.Stage;
import dev.devfest.event.domain.RoundTimer;
import dev.devfest.event.dto.AiRoomActivityView;
import dev.devfest.event.dto.AiRoomActivityView.Option;
import dev.devfest.event.dto.AiRoomActivityView.Question;
import dev.devfest.event.dto.AiRoomActivityView.Repair;
import dev.devfest.event.dto.AiRoomActivityView.Result;
import dev.devfest.event.dto.AiRoomActivityView.Section;
import dev.devfest.event.dto.AiRoomActivityView.Snippet;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.QuizEvaluationRecord;
import dev.devfest.event.repository.QuizMcqAnswerRecord;
import dev.devfest.event.repository.QuizQuestionRecord;
import dev.devfest.event.repository.QuizRepairAnswerRecord;
import dev.devfest.event.repository.QuizRepairRecord;
import dev.devfest.event.repository.QuizTeamProgressRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The team's view of the AI room. The only place that turns organizer records into what a team may see: no
 * correct flags, correct positions, explanations, points, totals or the pass score ever reach
 * {@link AiRoomActivityView}.
 */
final class AiRoomViews {

  /** Everything the view is built from, read by the activity service. */
  record MemberData(
    long teamId,
    EventRoomRecord room,
    RoundTimer timer,
    Instant now,
    List<QuizQuestionRecord> questions,
    List<QuizRepairRecord> repairs,
    List<QuizMcqAnswerRecord> mcqAnswers,
    List<QuizRepairAnswerRecord> repairAnswers,
    Optional<QuizTeamProgressRecord> progress,
    Optional<QuizEvaluationRecord> evaluation
  ) {}

  private AiRoomViews() {}

  static AiRoomActivityView member(MemberData data, byte[] secret) {
    boolean finished = data.progress().map(QuizTeamProgressRecord::mcqFinished).orElse(false);
    int mcqSeconds = data.room().quizMcqMinutes() * 60;
    Stage stage = QuizWindow.stage(data.timer(), data.now(), mcqSeconds, finished);
    Map<Long, QuizMcqAnswerRecord> answered = data.mcqAnswers().stream().collect(Collectors.toMap(QuizMcqAnswerRecord::questionId, Function.identity()));
    Map<Long, QuizRepairAnswerRecord> submitted = data.repairAnswers().stream().collect(Collectors.toMap(QuizRepairAnswerRecord::repairId, Function.identity()));
    Result result = data.evaluation().map(evaluation -> result(evaluation, data.room())).orElse(null);
    boolean open = result == null;
    return new AiRoomActivityView(
      data.room().id(),
      data.room().name(),
      stage,
      data.timer().effectivePhase(data.now()),
      data.now(),
      data.room().quizMcqMinutes(),
      stage == Stage.MCQ ? QuizWindow.mcqRemainingSeconds(data.timer(), data.now(), mcqSeconds) : 0,
      finished,
      data.questions().size(),
      answered.size(),
      data.repairs().size(),
      submitted.size(),
      sections(data.questions()),
      open && stage == Stage.MCQ ? questions(data, answered, secret) : List.of(),
      open && (stage == Stage.REPAIRS || stage == Stage.TIME_UP) ? repairs(data, stage, submitted, secret) : List.of(),
      result
    );
  }

  /** The clue follows the team's collected clue (an override or a manual grant changes it), never its score. */
  private static Result result(QuizEvaluationRecord evaluation, EventRoomRecord room) {
    return evaluation.clueCollected() ? new Result(true, room.clueKey()) : new Result(false, null);
  }

  private static List<Section> sections(List<QuizQuestionRecord> questions) {
    Map<QuizSection, Long> counts = questions.stream().collect(Collectors.groupingBy(QuizQuestionRecord::section, Collectors.counting()));
    List<Section> sections = new ArrayList<>();
    for (QuizSection section : QuizSection.values()) {
      sections.add(new Section(section, section.label(), section.paceSeconds(), counts.getOrDefault(section, 0L).intValue()));
    }
    return sections;
  }

  /** Section order is kept; inside a section, and among a question's options, the order is the team's own. */
  private static List<Question> questions(MemberData data, Map<Long, QuizMcqAnswerRecord> answered, byte[] secret) {
    List<Question> ordered = new ArrayList<>();
    for (QuizSection section : QuizSection.values()) {
      List<QuizQuestionRecord> inSection = data.questions().stream().filter(q -> q.section() == section).toList();
      long seed = QuizShuffle.seed(secret, Kind.SECTION, data.teamId(), section.ordinal());
      for (QuizQuestionRecord question : QuizShuffle.shuffle(inSection, seed)) {
        List<Option> options = QuizShuffle.shuffle(question.options(), QuizShuffle.seed(secret, Kind.OPTIONS, data.teamId(), question.id()))
          .stream()
          .map(option -> new Option(option.id(), option.text()))
          .toList();
        QuizMcqAnswerRecord answer = answered.get(question.id());
        ordered.add(
          new Question(question.id(), ordered.size() + 1, section, question.prompt(), options, answer == null ? null : answer.optionId())
        );
      }
    }
    return ordered;
  }

  /** While the repairs are open: instructions and snippets (shuffled, or as submitted); at Time's up titles only. */
  private static List<Repair> repairs(MemberData data, Stage stage, Map<Long, QuizRepairAnswerRecord> submitted, byte[] secret) {
    List<Repair> repairs = new ArrayList<>();
    for (QuizRepairRecord repair : data.repairs()) {
      QuizRepairAnswerRecord answer = submitted.get(repair.id());
      boolean open = stage == Stage.REPAIRS;
      repairs.add(
        new Repair(
          repair.id(),
          repairs.size() + 1,
          repair.title(),
          open ? repair.instructions() : null,
          open ? snippets(data.teamId(), repair, answer, secret) : List.of(),
          answer != null
        )
      );
    }
    return repairs;
  }

  private static List<Snippet> snippets(long teamId, QuizRepairRecord repair, QuizRepairAnswerRecord answer, byte[] secret) {
    Map<Long, String> code = repair.snippets().stream().collect(Collectors.toMap(QuizRepairRecord.Snippet::id, QuizRepairRecord.Snippet::code));
    List<Long> order = answer != null
      ? answer.snippetOrder()
      : QuizShuffle.shuffleAwayFrom(repair.correctOrder(), QuizShuffle.seed(secret, Kind.SNIPPETS, teamId, repair.id()));
    return order.stream().map(id -> new Snippet(id, code.get(id))).toList();
  }
}
