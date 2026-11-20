package dev.devfest.event.domain;

import dev.devfest.event.domain.ShipmentContent.Clue;
import dev.devfest.event.domain.ShipmentContent.KeyIndexEntry;
import dev.devfest.event.domain.ShipmentContent.Mission;
import dev.devfest.event.domain.ShipmentContent.Suspect;
import dev.devfest.event.domain.recovery.RecoveryContentRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Everything a "Last Shipment" content file (format 2) must satisfy. Every problem is listed; none stops the others.
 * Pure.
 */
public final class ShipmentContentRules {

  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
  private static final Pattern SUSPECT_ID = Pattern.compile("[A-Za-z0-9_-]{1,10}");
  private static final int MAX_TITLE = 120;
  private static final int MAX_TEXT = 2000;
  private static final int MAX_REPORT = 12_000;
  private static final int MAX_REFERENCE = 40;
  private static final int MAX_NAME = 80;

  private ShipmentContentRules() {}

  public static List<String> problems(ShipmentContent content) {
    if (content == null) return List.of("The file is empty.");
    List<String> problems = new ArrayList<>();
    if (!Integer.valueOf(ShipmentContent.FORMAT).equals(content.format())) {
      problems.add("format: must be " + ShipmentContent.FORMAT + ".");
    }
    if (content.fullUntilMinutes() == null || content.fullUntilMinutes() < 1 || content.fullUntilMinutes() > 40) {
      problems.add("fullUntilMinutes: must be a whole number from 1 to 40.");
    }
    Integer phase1 = content.phase1Minutes();
    if (phase1 != null && (phase1 < FinalPhase.MIN_PHASE1_MINUTES || phase1 > FinalPhase.MAX_PHASE1_MINUTES)) {
      problems.add(
        "phase1Minutes: must be a whole number from " + FinalPhase.MIN_PHASE1_MINUTES + " to " + FinalPhase.MAX_PHASE1_MINUTES + " (15 when left out)."
      );
    }
    mission(content.mission(), problems);
    Set<String> references = clues(content.clues(), problems);
    keyIndex(content.keyIndex(), references, problems);
    suspects(content, problems);
    problems.addAll(RecoveryContentRules.problems(content.recoveryLevels()));
    return problems;
  }

  private static void mission(Mission mission, List<String> problems) {
    if (mission == null) {
      problems.add("mission: title, intro, phase1 and phase2 are required.");
      return;
    }
    text("mission.title", mission.title(), MAX_TITLE, problems);
    text("mission.intro", mission.intro(), MAX_TEXT, problems);
    text("mission.phase1", mission.phase1(), MAX_TITLE, problems);
    text("mission.phase2", mission.phase2(), MAX_TITLE, problems);
  }

  /** Returns the clues' key references (for the key index check). */
  private static Set<String> clues(List<Clue> clues, List<String> problems) {
    Set<String> references = new HashSet<>();
    if (clues == null || clues.size() != ShipmentContent.CLUE_COUNT) {
      problems.add("clues: exactly " + ShipmentContent.CLUE_COUNT + " clues are required, numbered 1 to 4 in that order.");
      return references;
    }
    Set<String> files = new HashSet<>();
    for (int i = 0; i < clues.size(); i++) {
      Clue clue = clues.get(i);
      String where = "Clue " + (i + 1) + ": ";
      if (clue == null) {
        problems.add(where + "is empty.");
        continue;
      }
      if (clue.number() == null || clue.number() != i + 1) problems.add(where + "number must be " + (i + 1) + ".");
      text(where + "title", clue.title(), MAX_TITLE, problems);
      for (String file : new String[] { clue.encFile(), clue.rsaFile() }) {
        if (file == null || !FILE_NAME.matcher(file).matches()) {
          problems.add(where + "encFile and rsaFile must be plain file names (letters, digits, '.', '_', '-').");
        } else if (!files.add(file)) {
          problems.add(where + "the file name " + file + " is used twice.");
        }
      }
      if (clue.keyReference() == null || clue.keyReference().isBlank() || clue.keyReference().length() > MAX_REFERENCE) {
        problems.add(where + "keyReference is required (at most " + MAX_REFERENCE + " characters).");
      } else if (!references.add(clue.keyReference())) {
        problems.add(where + "keyReference " + clue.keyReference() + " is used by another clue.");
      }
      if (clue.passphraseSha256() == null || !SHA256_HEX.matcher(clue.passphraseSha256()).matches()) {
        problems.add(where + "passphraseSha256 must be 64 lower-case hex characters.");
      }
      text(where + "report", clue.report(), MAX_REPORT, problems);
    }
    return references;
  }

  private static void keyIndex(List<KeyIndexEntry> index, Set<String> references, List<String> problems) {
    if (index == null || index.size() != ShipmentContent.CLUE_COUNT) {
      problems.add("keyIndex: exactly " + ShipmentContent.CLUE_COUNT + " entries are required, one per clue.");
      return;
    }
    Map<String, Integer> keysUsed = new HashMap<>();
    Set<String> covered = new HashSet<>();
    for (KeyIndexEntry entry : index) {
      if (entry == null || entry.reference() == null || entry.key() == null) {
        problems.add("keyIndex: every entry needs reference and key.");
        continue;
      }
      if (!references.contains(entry.reference())) {
        problems.add("keyIndex: " + entry.reference() + " is not the keyReference of any clue.");
      } else if (!covered.add(entry.reference())) {
        problems.add("keyIndex: " + entry.reference() + " appears twice.");
      }
      if (!ShipmentContent.KEYS.contains(entry.key())) {
        problems.add("keyIndex " + entry.reference() + ": key must be one of A, B, C, D.");
      } else {
        keysUsed.merge(entry.key(), 1, Integer::sum);
      }
    }
    for (String key : ShipmentContent.KEYS) {
      int uses = keysUsed.getOrDefault(key, 0);
      if (uses != 1) problems.add("keyIndex: key " + key + " must be used exactly once (used " + uses + " times).");
    }
    for (String reference : references) {
      if (!covered.contains(reference)) problems.add("keyIndex: the clue reference " + reference + " has no entry.");
    }
  }

  private static void suspects(ShipmentContent content, List<String> problems) {
    List<Suspect> suspects = content.suspects();
    Set<String> ids = new HashSet<>();
    if (suspects == null || suspects.size() < ShipmentContent.MIN_SUSPECTS || suspects.size() > ShipmentContent.MAX_SUSPECTS) {
      problems.add("suspects: from " + ShipmentContent.MIN_SUSPECTS + " to " + ShipmentContent.MAX_SUSPECTS + " suspects are required.");
    }
    if (suspects != null) {
      for (int i = 0; i < suspects.size(); i++) {
        Suspect s = suspects.get(i);
        String where = "Suspect " + (i + 1) + ": ";
        if (s == null) {
          problems.add(where + "is empty.");
          continue;
        }
        if (s.id() == null || !SUSPECT_ID.matcher(s.id()).matches()) {
          problems.add(where + "id must be 1 to 10 letters, digits, '_' or '-'.");
        } else if (!ids.add(s.id())) {
          problems.add(where + "the id " + s.id() + " is used twice.");
        }
        text(where + "name", s.name(), MAX_NAME, problems);
        text(where + "position", s.position(), MAX_NAME, problems);
        text(where + "statement", s.statement(), MAX_TEXT, problems);
      }
    }
    if (content.correctSuspect() == null || !ids.contains(content.correctSuspect())) {
      problems.add("correctSuspect: must be the id of one of the suspects.");
    }
  }

  private static void text(String field, String value, int max, List<String> problems) {
    if (value == null || value.isBlank() || value.length() > max) {
      problems.add(field + " is required (at most " + max + " characters).");
    }
  }
}
