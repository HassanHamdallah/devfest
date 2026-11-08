package dev.devfest.event.domain.sentinel;

import java.util.List;

/** Confusion-matrix counts and metrics for a two-class camera (stage 5). Pure. */
public record Confusion(int tp, int fp, int fn, int tn) {

  /** One camera frame: whether a van was really there, and whether the camera said so. */
  public record Row(boolean actual, boolean predicted) {}

  /** The metric that matters when the cost is a missed van (a false negative): how many real vans were caught. */
  public static final String METRIC_FOR_MISSES = "Recall";

  public static Confusion of(List<Row> rows) {
    int tp = 0;
    int fp = 0;
    int fn = 0;
    int tn = 0;
    for (Row row : rows) {
      if (row.actual() && row.predicted()) tp++;
      else if (!row.actual() && row.predicted()) fp++;
      else if (row.actual()) fn++;
      else tn++;
    }
    return new Confusion(tp, fp, fn, tn);
  }

  /** TP / (TP + FP); 0 when the camera never said "van". */
  public double precision() {
    return tp + fp == 0 ? 0 : (double) tp / (tp + fp);
  }

  /** TP / (TP + FN); 0 when there was no van. */
  public double recall() {
    return tp + fn == 0 ? 0 : (double) tp / (tp + fn);
  }
}
