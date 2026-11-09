package dev.devfest.event.service;

import dev.devfest.event.domain.ContentRejectedException;
import dev.devfest.event.domain.CryptoContent;
import dev.devfest.event.domain.CryptoContentRules;
import dev.devfest.event.repository.FinalContentRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Reads and validates the final's content file; the stored copy was validated when it was imported. */
@Component
class FinalContentReader {

  static final int MAX_FILE_CHARS = 60_000;

  private final FinalContentRepository repository;
  private final ObjectMapper json;

  FinalContentReader(FinalContentRepository repository, ObjectMapper json) {
    this.repository = repository;
    this.json = json;
  }

  /** The imported content, or empty when the admin has not imported a file yet. One SQL statement. */
  Optional<CryptoContent> content() {
    return repository.find().map(stored -> read(stored.json()));
  }

  /** The "full points until" minutes of the imported file (the default while none is imported). One SQL statement. */
  int fullUntilMinutes() {
    return repository.find().map(FinalContentRepository.Stored::fullUntilMinutes).orElse(CryptoContent.DEFAULT_FULL_UNTIL_MINUTES);
  }

  /** Parses a file and returns it only when every rule holds; otherwise throws with every problem listed. */
  CryptoContent parseAndValidate(String body) {
    if (body == null || body.isBlank()) throw new ContentRejectedException(List.of("The file is empty."));
    if (body.length() > MAX_FILE_CHARS) {
      throw new ContentRejectedException(List.of("The file is larger than " + MAX_FILE_CHARS / 1000 + " KB."));
    }
    CryptoContent content = read(body);
    List<String> problems = CryptoContentRules.problems(content);
    if (!problems.isEmpty()) throw new ContentRejectedException(problems);
    return content;
  }

  private CryptoContent read(String body) {
    try {
      return json.readerFor(CryptoContent.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(body);
    } catch (JacksonException e) {
      throw new ContentRejectedException(List.of("The file is not valid crypto-final JSON: " + shorten(e.getOriginalMessage())));
    }
  }

  private static String shorten(String message) {
    if (message == null) return "unreadable.";
    String firstLine = message.lines().findFirst().orElse(message);
    return firstLine.length() > 160 ? firstLine.substring(0, 160) + "..." : firstLine;
  }
}
