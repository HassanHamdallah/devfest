package dev.devfest.event.domain.sentinel;

import java.util.List;
import java.util.Map;

/** What a team submits for a stage, already shaped (the reader refuses malformed JSON before this exists). */
public sealed interface StageAnswer {
  /** Stages 1, 3 and 10: the configuration chosen / deployed / locked. */
  record Pick(String config) implements StageAnswer {}

  /** Stage 2: for every feature, whether it is used. */
  record Features(Map<String, Boolean> use) implements StageAnswer {}

  /** Stage 4: the class at k=1 and at k=5, and the k the team trusts (1 or 5). */
  record Neighbours(String k1, String k5, int reliable) implements StageAnswer {}

  /** Stage 5: the four counts, precision and recall as decimals, and the metric that matters for a missed van. */
  record Matrix(int tp, int fp, int fn, int tn, double precision, double recall, String metric) implements StageAnswer {}

  /** Stage 6: the asked cluster's size and its final centroid. */
  record Cluster(int size, double x, double y) implements StageAnswer {}

  /** Stage 7: the nodes in expansion order, the route and its cost. */
  record Route(List<String> order, List<String> path, double cost) implements StageAnswer {}

  /** Stage 8: the two unnormalised values and the class. */
  record Bayes(double threat, double normal, String label) implements StageAnswer {}

  /** A CHOICE stage: the id of the option chosen (never its position). */
  record Option(String option) implements StageAnswer {}

  /** An INPUT stage: what the team typed, as typed (the server trims and compares it). */
  record Typed(String text) implements StageAnswer {}

  /** Stage 9: the final conclusion. */
  record Conclusion(String fact) implements StageAnswer {}
}
