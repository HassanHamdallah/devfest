package dev.devfest.event.dto;

import dev.devfest.event.domain.RoundPhase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The AI mission room as its team sees it (rules §19). Never carries points, totals, the pass score, whether
 * anything is correct, the scoring tables or any answer: a stage holds only its data, its experiments' results
 * (one per run) and whether it is saved. {@code missions} are sent only while the round runs or is paused and the
 * team has no result; once a stage is saved it is just "saved" (no data, no runs). {@code result} is null until the
 * team is evaluated.
 *
 * @param ready false when the room has no mission content yet
 * @param intro the story's opening text
 */
public record AiRoomActivityView(
  long roomId,
  String roomName,
  RoundPhase phase,
  Instant serverNow,
  boolean ready,
  String intro,
  int stageCount,
  int savedCount,
  List<Mission> missions,
  Result result
) {
  public record Mission(String id, String title, String intro, List<Stage> stages) {}

  /**
   * {@code data}: what the stage's screen draws, null once saved. {@code runs}: the team's experiments so far, in
   * order. {@code runsLeft}: different experiments still allowed, null when the stage has no limit or no
   * experiments.
   */
  public record Stage(
    int number,
    String type,
    String title,
    String fieldNote,
    String brief,
    boolean saved,
    Map<String, Object> data,
    List<Run> runs,
    Integer runsLeft
  ) {}

  /** One experiment: the configuration and the result the server returned for it. */
  public record Run(String config, JsonNode result) {}

  /**
   * The team's outcome: {@code clueUnlocked} when it holds the room's clue, with {@code clue} the room's current
   * official clue; otherwise both are empty. {@code message}: the story's closing text, never with a number.
   */
  public record Result(boolean clueUnlocked, String clue, String message) {}
}
