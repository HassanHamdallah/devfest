package dev.devfest.event.domain.sentinel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The shipped mission file and a perfect answer for each of its stages, shared by the AI room tests. */
public final class SentinelFixture {

  public static final Path FILE = Path.of("..", "docs", "ai-room", "sentinel-content.json");

  /** A perfect answer for each stage, in stage order (100 points in all). */
  public static final List<String> PERFECT = List.of(
    "{\"config\":\"iqr-1.5\"}",
    "{\"choices\":{\"entry-gate\":\"USE\",\"entry-time\":\"USE\",\"vehicle-type\":\"USE\",\"previous-visits\":\"USE\",\"plate-number\":\"DROP\",\"exit-camera-time\":\"DROP\"}}",
    "{\"config\":\"tree-5\"}",
    "{\"k1\":\"Car\",\"k5\":\"Van\",\"reliable\":5}",
    "{\"tp\":6,\"fp\":2,\"fn\":4,\"tn\":8,\"precision\":0.75,\"recall\":0.6,\"metric\":\"Recall\"}",
    "{\"size\":6,\"x\":7.5,\"y\":6.5}",
    "{\"order\":[\"lib\",\"lab\",\"dor\",\"adm\",\"gar\",\"g3\"],\"path\":[\"lib\",\"lab\",\"adm\",\"gar\",\"g3\"],\"cost\":15}",
    "{\"threat\":0.00012,\"normal\":0.0010125,\"label\":\"Normal\"}",
    "{\"conclusion\":\"insider-involved\"}",
    "{\"config\":\"large-1\"}"
  );

  private SentinelFixture() {}

  public static String shipped() {
    try {
      return Files.readString(FILE);
    } catch (IOException e) {
      throw new IllegalStateException("Run the tests from the backend folder: " + FILE.toAbsolutePath(), e);
    }
  }
}
