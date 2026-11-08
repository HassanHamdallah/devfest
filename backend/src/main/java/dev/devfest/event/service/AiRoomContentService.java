package dev.devfest.event.service;

import static dev.devfest.api.Errors.fail;

import dev.devfest.audit.AuditLog;
import dev.devfest.auth.AuthService.Principal;
import dev.devfest.event.domain.ContentRejectedException;
import dev.devfest.event.domain.RoomActivity;
import dev.devfest.event.domain.sentinel.SentinelContent;
import dev.devfest.event.domain.sentinel.SentinelContent.Mission;
import dev.devfest.event.domain.sentinel.SentinelContent.Stage;
import dev.devfest.event.domain.sentinel.SentinelContentRules;
import dev.devfest.event.dto.MissionDocument;
import dev.devfest.event.dto.MissionEditorView;
import dev.devfest.event.dto.RoomActivityView;
import dev.devfest.event.repository.EventRoomRecord;
import dev.devfest.event.repository.EventRoomRepository;
import dev.devfest.event.repository.EventStateRecord;
import dev.devfest.event.repository.EventStateRepository;
import dev.devfest.event.repository.SentinelContentRepository;
import dev.devfest.event.repository.SentinelEvaluationRepository;
import dev.devfest.event.repository.SentinelWorkRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The AI room's content (rules §19.2): the room's MANAGER (or ADMIN) imports and exports the whole mission as one
 * JSON file and sets the pass score; the ADMIN switches a room's activity on or off. Content is read-only after the
 * final opens and locked once any team has run or saved anything. A bad file is refused whole, with every problem
 * listed. Audits name the room and the number of stages, never the content.
 */
@Service
public class AiRoomContentService {

  /** A real mission file is about 60 KB; this keeps a wrong upload from being parsed at all. */
  static final int MAX_FILE_CHARS = 400_000;

  private final EventAccessPolicy access;
  private final EventStateRepository states;
  private final EventRoomRepository rooms;
  private final SentinelContentRepository content;
  private final SentinelWorkRepository work;
  private final SentinelEvaluationRepository evaluations;
  private final AuditLog audit;
  private final EventClock clock;
  private final ObjectMapper json;

  public AiRoomContentService(
    EventAccessPolicy access,
    EventStateRepository states,
    EventRoomRepository rooms,
    SentinelContentRepository content,
    SentinelWorkRepository work,
    SentinelEvaluationRepository evaluations,
    AuditLog audit,
    EventClock clock,
    ObjectMapper json
  ) {
    this.access = access;
    this.states = states;
    this.rooms = rooms;
    this.content = content;
    this.work = work;
    this.evaluations = evaluations;
    this.audit = audit;
    this.clock = clock;
    this.json = json;
  }

  /** 5 SQL statements: room (access check), state, content, whether any team worked, whether any was evaluated. */
  public MissionEditorView editor(Principal principal, long roomId) {
    EventRoomRecord room = access.requireManagerOf(principal, roomId);
    return editorView(room, states.state());
  }

  /** The pass score ({@code null} is not accepted by the request). Locked once a team of the room was evaluated. */
  @Transactional
  public MissionEditorView saveSettings(Principal principal, long roomId, int passScore) {
    access.requireManagerOf(principal, roomId);
    Locked locked = lockEditable(roomId);
    if (passScore != locked.room().quizPassScore()) {
      requirePassScoreOpen(roomId);
      rooms.setQuizPassScore(roomId, passScore);
      changed(principal, "EVENT_MISSION_SETTINGS", "room:" + roomId + " passScore:" + passScore);
    }
    return editorView(rooms.find(roomId).orElseThrow(), locked.state());
  }

  /** The room's mission as an import file (it holds the scoring tables: organizers only), with the current pass score. */
  public JsonNode export(Principal principal, long roomId) {
    EventRoomRecord room = access.requireManagerOf(principal, roomId);
    String stored = content.document(roomId).orElseThrow(() -> fail(409, "This room has no mission content yet."));
    ObjectNode file = json.createObjectNode();
    file.put("format", SentinelContent.FORMAT);
    file.put("passScore", room.quizPassScore());
    json.readTree(stored).properties().forEach(entry -> file.set(entry.getKey(), entry.getValue()));
    return file;
  }

  /**
   * Replaces the room's mission with the file's. The file is read and checked first (400 listing every problem);
   * refused (409) once any team has run or saved anything in the room, or when the file changes a locked pass score.
   */
  @Transactional
  public MissionEditorView importDocument(Principal principal, long roomId, String body) {
    access.requireManagerOf(principal, roomId);
    JsonNode root = tree(body);
    MissionDocument document = MissionDocument.read(root);
    Locked locked = lockEditable(roomId);
    if (work.anyWork(roomId)) throw fail(409, "Teams have already worked in this room; its mission can no longer be replaced.");
    Integer passScore = document.passScore();
    if (passScore != null && passScore != locked.room().quizPassScore()) requirePassScoreOpen(roomId);
    ObjectNode stored = ((ObjectNode) root).deepCopy();
    stored.remove("passScore");
    content.replace(roomId, json.writeValueAsString(stored), principal.id(), clock.now());
    if (passScore != null) rooms.setQuizPassScore(roomId, passScore);
    changed(principal, "EVENT_MISSION_IMPORTED", "room:" + roomId + " stages:" + SentinelContent.STAGE_COUNT);
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

  private JsonNode tree(String body) {
    if (body == null || body.isBlank()) throw new ContentRejectedException(List.of("The file is empty."));
    if (body.length() > MAX_FILE_CHARS) throw new ContentRejectedException(List.of("The file is larger than " + MAX_FILE_CHARS / 1000 + " KB."));
    try {
      return json.readTree(body);
    } catch (JacksonException e) {
      throw new ContentRejectedException(List.of("The file is not valid JSON."));
    }
  }

  private record Locked(EventStateRecord state, EventRoomRecord room) {}

  /** Lock order event_state → room; content is read-only after the final opens and needs the room's mission on. */
  private Locked lockEditable(long roomId) {
    EventStateRecord state = states.lockForUpdate();
    EventContentService.requireContentEditable(state);
    EventRoomRecord room = rooms.lockForUpdate(roomId).orElseThrow(() -> fail(404, "Room not found."));
    if (!room.hasMission()) throw fail(409, "The AI mission is not enabled for this room.");
    return new Locked(state, room);
  }

  /** Every team of the room is judged against the same pass score: it is locked by the first evaluation. */
  private void requirePassScoreOpen(long roomId) {
    if (evaluations.anyForRoom(roomId)) {
      throw fail(409, "A team in this room has already been evaluated; the pass score can no longer change.");
    }
  }

  private MissionEditorView editorView(EventRoomRecord room, EventStateRecord state) {
    Optional<SentinelContent> mission = content.document(room.id()).map(text -> MissionDocument.load(json.readTree(text)));
    List<MissionEditorView.StageSummary> stages = new ArrayList<>();
    mission.ifPresent(m -> {
      for (Mission part : m.missions()) {
        for (Stage stage : part.stages()) {
          stages.add(new MissionEditorView.StageSummary(stage.number(), SentinelContentRules.kind(stage), part.id(), stage.head().title(), stage.head().maxPoints()));
        }
      }
    });
    return new MissionEditorView(
      room.id(),
      room.name(),
      room.activity(),
      room.quizPassScore(),
      evaluations.anyForRoom(room.id()),
      !state.finalEverOpened(),
      work.anyWork(room.id()),
      mission.isPresent(),
      stages,
      mission.map(SentinelContent::totalPoints).orElse(BigDecimal.ZERO)
    );
  }

  private void changed(Principal principal, String action, String target) {
    states.bumpRevision();
    audit.record(principal.id(), action, target);
  }
}
