package dev.devfest.event.service;

import dev.devfest.event.domain.ContentRejectedException;
import dev.devfest.event.domain.ShipmentContent;
import dev.devfest.event.domain.ShipmentContentRules;
import dev.devfest.event.repository.FinalContentRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads and validates the final's content file; the stored copy was validated when it was imported. */
@Component
class FinalContentReader {

  static final int MAX_FILE_CHARS = 60_000;
  static final String RETIRED_RECOVERY =
    "recovery: the single Recovery Challenge question is retired. Replace the \"recovery\" block with \"recoveryLevels\" " +
    "(\"ml\" and \"arduino\"), as in docs/final/last-shipment-content.json.";

  private final FinalContentRepository repository;
  private final ObjectMapper json;

  FinalContentReader(FinalContentRepository repository, ObjectMapper json) {
    this.repository = repository;
    this.json = json;
  }

  /** The imported content, or empty when the admin has not imported a file yet. One SQL statement. */
  Optional<ShipmentContent> content() {
    return repository.find().map(stored -> read(stored.json()));
  }

  /** The "full points until" minutes of the imported file (the default while none is imported). One SQL statement. */
  int fullUntilMinutes() {
    return repository.find().map(FinalContentRepository.Stored::fullUntilMinutes).orElse(ShipmentContent.DEFAULT_FULL_UNTIL_MINUTES);
  }

  /** Parses a file and returns it only when every rule holds; otherwise throws with every problem listed. */
  ShipmentContent parseAndValidate(String body) {
    if (body == null || body.isBlank()) throw new ContentRejectedException(List.of("The file is empty."));
    if (body.length() > MAX_FILE_CHARS) {
      throw new ContentRejectedException(List.of("The file is larger than " + MAX_FILE_CHARS / 1000 + " KB."));
    }
    refuseRetiredRecovery(body);
    ShipmentContent content = read(body);
    List<String> problems = ShipmentContentRules.problems(content);
    if (!problems.isEmpty()) throw new ContentRejectedException(problems);
    return content;
  }

  /** A file written for the retired single Recovery Challenge says so plainly instead of failing as an unknown field. */
  private void refuseRetiredRecovery(String body) {
    JsonNode tree;
    try {
      tree = json.readTree(body);
    } catch (JacksonException e) {
      return; // reported by read() with the parser's message
    }
    if (tree != null && tree.isObject() && tree.has("recovery")) throw new ContentRejectedException(List.of(RETIRED_RECOVERY));
  }

  private ShipmentContent read(String body) {
    try {
      return json.readerFor(ShipmentContent.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(body);
    } catch (JacksonException e) {
      throw new ContentRejectedException(List.of("The file is not valid final content JSON (format 2): " + shorten(e.getOriginalMessage())));
    }
  }

  private static String shorten(String message) {
    if (message == null) return "unreadable.";
    String firstLine = message.lines().findFirst().orElse(message);
    return firstLine.length() > 160 ? firstLine.substring(0, 160) + "..." : firstLine;
  }
}
