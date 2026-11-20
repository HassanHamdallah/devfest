package dev.devfest.event.domain;

import dev.devfest.event.domain.recovery.RecoveryLevels;
import java.util.List;
import java.util.Optional;

/**
 * The final's content, "The Last Shipment" (format 2, docs/final/last-shipment-content.json), as imported by the admin.
 * Fields are boxed so a missing value is reported by {@link ShipmentContentRules} instead of failing the read.
 *
 * <p>Each of the four clues is an encrypted report: the team decrypts the clue's RSA file with the private key the key
 * index names, gets the passphrase, and enters it. The file holds only the passphrase's SHA-256, never the passphrase.
 * A member may receive the mission texts, each clue's title, file names and key reference, the key index, the suspects
 * (id, name, position, statement) and the report of a clue the team solved; never a report of an unsolved clue, a
 * passphrase hash, the correct suspect or a recovery level's answers ({@link RecoveryLevels}: the views copy only their
 * public parts).
 */
public record ShipmentContent(
  Integer format,
  Integer fullUntilMinutes,
  Integer phase1Minutes,
  Mission mission,
  List<Clue> clues,
  List<KeyIndexEntry> keyIndex,
  List<Suspect> suspects,
  String correctSuspect,
  RecoveryLevels recoveryLevels
) {
  public static final int FORMAT = 2;
  public static final int CLUE_COUNT = 4;
  public static final List<String> KEYS = List.of("A", "B", "C", "D");
  public static final int MIN_SUSPECTS = 2;
  public static final int MAX_SUSPECTS = 30;
  public static final int DEFAULT_FULL_UNTIL_MINUTES = 25;

  public record Mission(String title, String intro, String phase1, String phase2) {}

  /** One evidence file: {@code passphraseSha256} is the lower-case hex SHA-256 of the 64-hex passphrase. */
  public record Clue(
    Integer number,
    String title,
    String encFile,
    String rsaFile,
    String keyReference,
    String passphraseSha256,
    String report
  ) {}

  /** Which private key (A to D) a clue's key reference points at. */
  public record KeyIndexEntry(String reference, String key) {}

  public record Suspect(String id, String name, String position, String statement) {}

  /** How long the decryption phase lasts (minutes of the final's active time); the default when the file has no value. */
  public int phase1OrDefault() {
    return phase1Minutes == null ? FinalPhase.DEFAULT_PHASE1_MINUTES : phase1Minutes;
  }

  /** The clue numbered {@code number} (1 to 4); the validated file lists them in that order. */
  public Clue clue(int number) {
    return clues.get(number - 1);
  }

  /** True when the typed passphrase opens the clue (see {@link Passphrase}). */
  public boolean opens(int clueNumber, String typed) {
    return Passphrase.matches(typed, clue(clueNumber).passphraseSha256());
  }

  public Optional<Suspect> suspect(String id) {
    return suspects.stream().filter(s -> s.id().equals(id)).findFirst();
  }

  public boolean isCorrectSuspect(String id) {
    return correctSuspect.equals(id);
  }
}
