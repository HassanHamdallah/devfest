package dev.devfest.event.domain.sentinel;

import java.util.List;
import java.util.Map;

/** Naive Bayes on a message's words (stage 8): prior times the product of each word's probability. Pure. */
public final class NaiveBayes {

  public static final String THREAT = "Threat";
  public static final String NORMAL = "Normal";

  /** P(word | Threat) and P(word | Normal). */
  public record WordOdds(double threat, double normal) {}

  /** The two unnormalised values and the class with the larger one (a tie is "Normal"). */
  public record Verdict(double threat, double normal, String label) {}

  private NaiveBayes() {}

  public static Verdict classify(double priorThreat, double priorNormal, List<String> message, Map<String, WordOdds> table) {
    double threat = priorThreat;
    double normal = priorNormal;
    for (String word : message) {
      WordOdds odds = table.get(word);
      if (odds == null) throw new IllegalArgumentException("Unknown word " + word);
      threat *= odds.threat();
      normal *= odds.normal();
    }
    return new Verdict(threat, normal, threat > normal ? THREAT : NORMAL);
  }
}
