package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.QuizContentRules;
import dev.devfest.event.domain.QuizSection;
import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.dto.QuizDocument;
import dev.devfest.event.dto.QuizEditorView;
import dev.devfest.event.dto.QuizQuestionRequest;
import dev.devfest.event.dto.QuizRepairRequest;
import dev.devfest.event.dto.RoomActivityView;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.QuizContentRepository;
import dev.devfest.event.repository.QuizContentRepository.NewOption;
import dev.devfest.event.repository.QuizEvaluationRepository;
import dev.devfest.event.repository.QuizQuestionRecord;
import dev.devfest.event.repository.QuizRepairRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The AI room's content (rules §19.2): the room's MANAGER (or ADMIN) authors questions and repairs, imports and
 * exports them as one JSON file and sets the MCQ minutes and pass score; the ADMIN switches a room's activity on or off. Content
 * is read-only after the final opens, and a question or repair is locked once a team answered it. Audits name the
 * room and the item, never prompts, options or snippets.
 */
@Service
public class AiRoomContentService {

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventRoomRepository rooms;
  private final QuizContentRepository content;
  private final QuizEvaluationRepository evaluations;
  private final AuditLog audit;
  private final EventClock clock;

  public AiRoomContentService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventRoomRepository rooms,
    QuizContentRepository content,
    QuizEvaluationRepository evaluations,
    AuditLog audit,
    EventClock clock
  ) {
    this.access = access;
    this.states = states;
    this.rooms = rooms;
    this.content = content;
    this.evaluations = evaluations;
    this.audit = audit;
    this.clock = clock;
  }

  /** A question or repair checked and trimmed, ready to store. */
  private record Question(QuizSection section, String prompt, List<String> options, int correctOption, int points, String explanation) {}

  private record Repair(String title, String instructions, int points, List<String> snippets) {}

  /** 5 SQL statements: room (access check), state, questions with options, repairs with snippets, evaluated yet. */
  public QuizEditorView editor(Principal principal, long roomId) {
    EventRoomRecord room = access.requireManagerOf(principal, roomId);
    return editorView(room, states.state());
  }

  @Transactional
  public QuizEditorView createQuestion(Principal principal, long roomId, QuizQuestionRequest request) {
    access.requireManagerOf(principal, roomId);
    Question question = parse(request);
    Locked locked = lockEditable(roomId);
    if (content.questions(roomId).size() >= QuizContentRules.MAX_QUESTIONS) {
      throw fail(409, "A room can have at most " + QuizContentRules.MAX_QUESTIONS + " questions.");
    }
    long id = insert(roomId, question, principal.id(), clock.now());
    changed(principal, "EVENT_QUIZ_QUESTION_CREATED", "room:" + roomId + " question:" + id + " section:" + question.section());
    return editorView(locked.room(), locked.state());
  }

  @Transactional
  public QuizEditorView updateQuestion(Principal principal, long roomId, long questionId, QuizQuestionRequest request) {
    access.requireManagerOf(principal, roomId);
    Question question = parse(request);
    Locked locked = lockEditable(roomId);
    requireUnanswered(content.question(roomId, questionId).orElseThrow(() -> fail(404, "Question not found.")));
    content.updateQuestion(questionId, question.section(), question.prompt(), question.points(), question.explanation(), principal.id(), clock.now());
    content.replaceOptions(questionId, options(question));
    changed(principal, "EVENT_QUIZ_QUESTION_UPDATED", "room:" + roomId + " question:" + questionId + " section:" + question.section());
    return editorView(locked.room(), locked.state());
  }

  @Transactional
  public QuizEditorView deleteQuestion(Principal principal, long roomId, long questionId) {
    access.requireManagerOf(principal, roomId);
    Locked locked = lockEditable(roomId);
    requireUnanswered(content.question(roomId, questionId).orElseThrow(() -> fail(404, "Question not found.")));
    content.deleteQuestion(questionId);
    changed(principal, "EVENT_QUIZ_QUESTION_DELETED", "room:" + roomId + " question:" + questionId);
    return editorView(locked.room(), locked.state());
  }

  @Transactional
  public QuizEditorView createRepair(Principal principal, long roomId, QuizRepairRequest request) {
    access.requireManagerOf(principal, roomId);
    Repair repair = parse(request);
    Locked locked = lockEditable(roomId);
    if (content.repairs(roomId).size() >= QuizContentRules.MAX_REPAIRS) {
      throw fail(409, "A room can have at most " + QuizContentRules.MAX_REPAIRS + " repairs.");
    }
    long id = insert(roomId, repair, principal.id(), clock.now());
    changed(principal, "EVENT_QUIZ_REPAIR_CREATED", "room:" + roomId + " repair:" + id);
    return editorView(locked.room(), locked.state());
  }

  @Transactional
  public QuizEditorView updateRepair(Principal principal, long roomId, long repairId, QuizRepairRequest request) {
    access.requireManagerOf(principal, roomId);
    Repair repair = parse(request);
    Locked locked = lockEditable(roomId);
    requireUnanswered(content.repair(roomId, repairId).orElseThrow(() -> fail(404, "Repair not found.")));
    content.updateRepair(repairId, repair.title(), repair.instructions(), repair.points(), principal.id(), clock.now());
    content.replaceSnippets(repairId, repair.snippets());
    changed(principal, "EVENT_QUIZ_REPAIR_UPDATED", "room:" + roomId + " repair:" + repairId);
    return editorView(locked.room(), locked.state());
  }

  @Transactional
  public QuizEditorView deleteRepair(Principal principal, long roomId, long repairId) {
    access.requireManagerOf(principal, roomId);
    Locked locked = lockEditable(roomId);
    requireUnanswered(content.repair(roomId, repairId).orElseThrow(() -> fail(404, "Repair not found.")));
    content.deleteRepair(repairId);
    changed(principal, "EVENT_QUIZ_REPAIR_DELETED", "room:" + roomId + " repair:" + repairId);
    return editorView(locked.room(), locked.state());
  }

  /**
   * The MCQ cutoff in running minutes (takes effect at once, also during a round) and the pass score ({@code null}
   * keeps it). The pass score is locked once a team of the room was evaluated (409); the cutoff stays editable.
   */
  @Transactional
  public QuizEditorView saveSettings(Principal principal, long roomId, int minutes, Integer passScore) {
    access.requireManagerOf(principal, roomId);
    Locked locked = lockEditable(roomId);
    if (locked.room().quizMcqMinutes() != minutes) {
      rooms.setQuizMcqMinutes(roomId, minutes);
      changed(principal, "EVENT_QUIZ_SETTINGS", "room:" + roomId + " mcqMinutes:" + minutes);
    }
    if (passScore != null && passScore != locked.room().quizPassScore()) {
      requirePassScoreOpen(roomId);
      rooms.setQuizPassScore(roomId, passScore);
      changed(principal, "EVENT_QUIZ_SETTINGS", "room:" + roomId + " passScore:" + passScore);
    }
    return editorView(rooms.find(roomId).orElseThrow(), locked.state());
  }

  /** The room's content as an import file; questions and repairs in authoring order, snippets in correct order. */
  public QuizDocument export(Principal principal, long roomId) {
    EventRoomRecord room = access.requireManagerOf(principal, roomId);
    List<QuizQuestionRequest> questions = content
      .questions(roomId)
      .stream()
      .map(q ->
        new QuizQuestionRequest(q.section().name(), q.prompt(), q.options().stream().map(QuizQuestionRecord.Option::text).toList(), q.correctIndex(), q.points(), q.explanation())
      )
      .toList();
    List<QuizRepairRequest> repairs = content
      .repairs(roomId)
      .stream()
      .map(r -> new QuizRepairRequest(r.title(), r.instructions(), r.points(), r.snippets().stream().map(QuizRepairRecord.Snippet::code).toList()))
      .toList();
    return new QuizDocument(QuizDocument.FORMAT, room.quizMcqMinutes(), room.quizPassScore(), questions, repairs);
  }

  /**
   * Replaces all of the room's questions and repairs with the file's. Every item is checked first (400 naming the
   * item); refused (409) once any team has answered anything in the room.
   */
  @Transactional
  public QuizEditorView importDocument(Principal principal, long roomId, QuizDocument document) {
    access.requireManagerOf(principal, roomId);
    if (document.format() != null && document.format() != QuizDocument.FORMAT) throw fail(400, "Unknown file format " + document.format() + ".");
    List<QuizQuestionRequest> questionRequests = Objects.requireNonNullElse(document.questions(), List.of());
    List<QuizRepairRequest> repairRequests = Objects.requireNonNullElse(document.repairs(), List.of());
    if (questionRequests.size() > QuizContentRules.MAX_QUESTIONS) throw fail(400, "A room can have at most " + QuizContentRules.MAX_QUESTIONS + " questions.");
    if (repairRequests.size() > QuizContentRules.MAX_REPAIRS) throw fail(400, "A room can have at most " + QuizContentRules.MAX_REPAIRS + " repairs.");
    Integer minutes = document.mcqMinutes();
    if (minutes != null && (minutes < 1 || minutes > 180)) throw fail(400, "mcqMinutes must be between 1 and 180.");
    Integer passScore = document.passScore();
    if (passScore != null && (passScore < 0 || passScore > 100)) throw fail(400, "passScore must be between 0 and 100.");
    List<Question> questions = new ArrayList<>();
    for (int i = 0; i < questionRequests.size(); i++) questions.add(parse(questionRequests.get(i), "Question " + (i + 1) + ": "));
    List<Repair> repairs = new ArrayList<>();
    for (int i = 0; i < repairRequests.size(); i++) repairs.add(parse(repairRequests.get(i), "Repair " + (i + 1) + ": "));

    Locked locked = lockEditable(roomId);
    if (content.anyAnswers(roomId)) throw fail(409, "Teams have already answered in this room; its content can no longer be replaced.");
    if (passScore != null && passScore != locked.room().quizPassScore()) requirePassScoreOpen(roomId);
    content.deleteAll(roomId);
    Instant now = clock.now();
    for (Question question : questions) insert(roomId, question, principal.id(), now);
    for (Repair repair : repairs) insert(roomId, repair, principal.id(), now);
    if (minutes != null) rooms.setQuizMcqMinutes(roomId, minutes);
    if (passScore != null) rooms.setQuizPassScore(roomId, passScore);
    changed(principal, "EVENT_QUIZ_IMPORTED", "room:" + roomId + " questions:" + questions.size() + " repairs:" + repairs.size());
    return editorView(rooms.find(roomId).orElseThrow(), locked.state());
  }

  /** ADMIN only, at any time (a delivery setting, like the terminal); a repeat is a no-op. */
  @Transactional
  public RoomActivityView setActivity(Principal principal, long roomId, RoomActivity activity) {
    access.requireAdmin(principal);
    states.lockForUpdate();
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, "Room not found."));
    if (room.activity() != activity) {
      rooms.setActivity(roomId, activity);
      changed(principal, "EVENT_ROOM_ACTIVITY", "room:" + roomId + " activity:" + (activity == null ? "NONE" : activity.name()));
    }
    return new RoomActivityView(roomId, activity);
  }

  private record Locked(EventStateRecord state, EventRoomRecord room) {}

  /** Lock order event_state → room; content is read-only after the final opens and needs the room's quiz on. */
  private Locked lockEditable(long roomId) {
    EventStateRecord state = states.lockForUpdate();
    EventContentService.requireContentEditable(state);
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, "Room not found."));
    if (!room.hasQuiz()) throw fail(409, "The AI quiz is not enabled for this room.");
    return new Locked(state, room);
  }

  /** Every team of the room is judged against the same pass score: it is locked by the first evaluation. */
  private void requirePassScoreOpen(long roomId) {
    if (evaluations.anyForRoom(roomId)) {
      throw fail(409, "A team in this room has already been evaluated; the pass score can no longer change.");
    }
  }

  private static void requireUnanswered(QuizQuestionRecord question) {
    if (question.answered()) throw fail(409, "A team has already answered this question; it can no longer be changed.");
  }

  private static void requireUnanswered(QuizRepairRecord repair) {
    if (repair.answered()) throw fail(409, "A team has already submitted this repair; it can no longer be changed.");
  }

  private long insert(long roomId, Question q, long by, Instant at) {
    long id = content.insertQuestion(roomId, q.section(), q.prompt(), q.points(), q.explanation(), by, at);
    content.replaceOptions(id, options(q));
    return id;
  }

  private long insert(long roomId, Repair r, long by, Instant at) {
    long id = content.insertRepair(roomId, r.title(), r.instructions(), r.points(), by, at);
    content.replaceSnippets(id, r.snippets());
    return id;
  }

  private static List<NewOption> options(Question q) {
    List<NewOption> options = new ArrayList<>();
    for (int i = 0; i < q.options().size(); i++) options.add(new NewOption(i, q.options().get(i), i == q.correctOption()));
    return options;
  }

  private static Question parse(QuizQuestionRequest request) {
    return parse(request, "");
  }

  /** Trims texts (snippet code is kept exactly as written); {@code prefix} names the item in an import. */
  private static Question parse(QuizQuestionRequest request, String prefix) {
    if (request == null) throw fail(400, prefix + "Enter the question.");
    QuizSection section = section(request.section(), prefix);
    List<String> options = request.options() == null ? null : request.options().stream().map(o -> o == null ? null : o.strip()).toList();
    String prompt = request.prompt() == null ? null : request.prompt().strip();
    String explanation = request.explanation() == null ? "" : request.explanation().strip();
    QuizContentRules.questionProblem(section, prompt, options, request.correctOption(), request.points(), explanation).ifPresent(problem -> {
      throw fail(400, prefix + problem);
    });
    return new Question(section, prompt, options, request.correctOption(), request.points(), explanation);
  }

  private static Repair parse(QuizRepairRequest request) {
    return parse(request, "");
  }

  private static Repair parse(QuizRepairRequest request, String prefix) {
    if (request == null) throw fail(400, prefix + "Enter a title.");
    String title = request.title() == null ? null : request.title().strip();
    String instructions = request.instructions() == null ? "" : request.instructions().strip();
    int points = request.points() == null ? QuizContentRules.DEFAULT_REPAIR_POINTS : request.points();
    QuizContentRules.repairProblem(title, instructions, points, request.snippets()).ifPresent(problem -> {
      throw fail(400, prefix + problem);
    });
    return new Repair(title, instructions, points, List.copyOf(request.snippets()));
  }

  private static QuizSection section(String value, String prefix) {
    if (value == null || value.isBlank()) return null;
    try {
      return QuizSection.valueOf(value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw fail(400, prefix + "Unknown section " + value + " (use EASY, MEDIUM, ADVANCED, LANDMARK or RECENT).");
    }
  }

  private QuizEditorView editorView(EventRoomRecord room, EventStateRecord state) {
    List<QuizQuestionRecord> questions = content.questions(room.id());
    List<QuizRepairRecord> repairs = content.repairs(room.id());
    Map<QuizSection, Integer> counts = new EnumMap<>(QuizSection.class);
    Map<QuizSection, Integer> points = new EnumMap<>(QuizSection.class);
    for (QuizQuestionRecord q : questions) {
      counts.merge(q.section(), 1, Integer::sum);
      points.merge(q.section(), q.points(), Integer::sum);
    }
    List<QuizEditorView.SectionTotal> sections = new ArrayList<>();
    for (QuizSection section : QuizSection.values()) {
      sections.add(new QuizEditorView.SectionTotal(section, section.label(), counts.getOrDefault(section, 0), section.expectedCount(), points.getOrDefault(section, 0)));
    }
    int mcqPoints = questions.stream().mapToInt(QuizQuestionRecord::points).sum();
    int repairPoints = repairs.stream().mapToInt(QuizRepairRecord::points).sum();
    List<QuizEditorView.Repair> repairViews = new ArrayList<>();
    for (QuizRepairRecord r : repairs) {
      repairViews.add(
        new QuizEditorView.Repair(r.id(), repairViews.size() + 1, r.title(), r.instructions(), r.points(), r.snippets().stream().map(QuizRepairRecord.Snippet::code).toList(), r.answered())
      );
    }
    return new QuizEditorView(
      room.id(),
      room.name(),
      room.activity(),
      room.quizMcqMinutes(),
      room.quizPassScore(),
      evaluations.anyForRoom(room.id()),
      !state.finalEverOpened(),
      questions.stream().anyMatch(QuizQuestionRecord::answered) || repairs.stream().anyMatch(QuizRepairRecord::answered),
      questions
        .stream()
        .map(q ->
          new QuizEditorView.Question(q.id(), q.section(), q.prompt(), q.options().stream().map(QuizQuestionRecord.Option::text).toList(), q.correctIndex(), q.points(), q.explanation(), q.answered())
        )
        .toList(),
      repairViews,
      sections,
      mcqPoints,
      repairPoints,
      mcqPoints + repairPoints,
      QuizContentRules.warnings(counts, repairs.size(), mcqPoints + repairPoints)
    );
  }

  private void changed(Principal principal, String action, String target) {
    states.bumpRevision();
    audit.record(principal.id(), action, target);
  }
}
