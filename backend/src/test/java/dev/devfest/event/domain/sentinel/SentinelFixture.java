package dev.devfest.event.domain.sentinel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The shipped mission file and a perfect answer for each of its stages, shared by the AI room tests. */
public final class SentinelFixture {

  public static final Path FILE = Path.of("..", "docs", "ai-room", "sentinel-content.json");
  /** The mission file as it was before the multiple choice stages (kept in the repository for reference and tests). */
  /** The mission file with multiple choice stages 6, 8 and 9 (before they became typed answers). */
  /** The mission file as it was before the formulas got their own "$$ " lines (only brief and field note line breaks differ). */
  public static final Path V3_FILE = Path.of("..", "docs", "ai-room", "sentinel-content.v3-backup.json");
  public static final Path V2_FILE = Path.of("..", "docs", "ai-room", "sentinel-content.v2-backup.json");
  public static final Path V1_FILE = Path.of("..", "docs", "ai-room", "sentinel-content.v1-backup.json");

  /** A perfect answer for each stage, in stage order (100 points in all). */
  public static final List<String> PERFECT = List.of(
    "{\"config\":\"iqr-1.5\"}",
    "{\"choices\":{\"entry-gate\":\"USE\",\"entry-time\":\"USE\",\"vehicle-type\":\"USE\",\"previous-visits\":\"USE\",\"plate-number\":\"DROP\",\"exit-camera-time\":\"DROP\"}}",
    "{\"option\":\"a\"}",
    "{\"k1\":\"Car\",\"k5\":\"Van\",\"reliable\":5}",
    "{\"tp\":6,\"fp\":2,\"fn\":4,\"tn\":8,\"precision\":0.75,\"recall\":0.6,\"metric\":\"Recall\"}",
    "{\"answer\":\"101001\"}",
    "{\"order\":[\"lib\",\"lab\",\"dor\",\"adm\",\"gar\",\"g3\"],\"path\":[\"lib\",\"lab\",\"adm\",\"gar\",\"g3\"],\"cost\":15}",
    "{\"answer\":\"0.19\"}",
    "{\"answer\":\"4\"}",
    "{\"config\":\"large-1\"}"
  );


  private SentinelFixture() {}

  public static String v1() {
    try {
      return Files.readString(V1_FILE);
    } catch (IOException e) {
      throw new IllegalStateException("Run the tests from the backend folder: " + V1_FILE.toAbsolutePath(), e);
    }
  }

  public static String v3() {
    try {
      return Files.readString(V3_FILE);
    } catch (IOException e) {
      throw new IllegalStateException("Run the tests from the backend folder: " + V3_FILE.toAbsolutePath(), e);
    }
  }

  public static String v2() {
    try {
      return Files.readString(V2_FILE);
    } catch (IOException e) {
      throw new IllegalStateException("Run the tests from the backend folder: " + V2_FILE.toAbsolutePath(), e);
    }
  }

  public static String shipped() {
    try {
      return Files.readString(FILE);
    } catch (IOException e) {
      throw new IllegalStateException("Run the tests from the backend folder: " + FILE.toAbsolutePath(), e);
    }
  }
}
