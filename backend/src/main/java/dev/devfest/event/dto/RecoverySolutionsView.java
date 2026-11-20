package dev.devfest.event.dto;

import dev.devfest.event.domain.ShipmentContent;
import dev.devfest.event.domain.recovery.RecoverySolutions;
import dev.devfest.event.domain.recovery.RecoverySolutions.ArduinoSolution;
import dev.devfest.event.domain.recovery.RecoverySolutions.MlSolution;
import java.util.List;

/**
 * The organizers' "Recovery solutions" (admin only): every ML answer with its reason, the Arduino level's bugs, fixed
 * lines, right option and why each wrong option fails, and which private key (A to D) opens each evidence file. It is
 * built from the imported content, so it carries every answer: never sent to a member.
 */
public record RecoverySolutionsView(List<MlSolution> ml, ArduinoSolution arduino, List<CaseKey> caseKeys) {

  /** One evidence file with the key reference its card shows and the private key letter that opens it. */
  public record CaseKey(int clue, String title, String encFile, String rsaFile, String keyReference, String key) {}

  public static RecoverySolutionsView of(RecoverySolutions.Solutions solutions, ShipmentContent content) {
    List<CaseKey> keys = content
      .clues()
      .stream()
      .map(c -> new CaseKey(
        c.number(),
        c.title(),
        c.encFile(),
        c.rsaFile(),
        c.keyReference(),
        content.keyIndex().stream().filter(k -> k.reference().equals(c.keyReference())).map(ShipmentContent.KeyIndexEntry::key).findFirst().orElse(null)
      ))
      .toList();
    return new RecoverySolutionsView(solutions.ml(), solutions.arduino(), keys);
  }
}
