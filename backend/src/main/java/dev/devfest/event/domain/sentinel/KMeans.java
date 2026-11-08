package dev.devfest.event.domain.sentinel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Lloyd's K-Means from fixed starting centroids (stage 6): Assign, then Update, until nothing moves. Pure. */
public final class KMeans {

  public record Point(String id, double x, double y) {}

  public record Centre(double x, double y) {}

  /**
   * @param assignment per point (same order as the input), the index of its final centroid
   * @param centres the final centroids
   * @param ambiguous a point was exactly as far from two centroids at some assignment (its cluster would depend on a tie rule)
   * @param emptyCluster some cluster lost all its points at some update
   */
  public record Result(int[] assignment, List<Centre> centres, int rounds, boolean ambiguous, boolean emptyCluster) {

    public int size(int cluster) {
      int count = 0;
      for (int a : assignment) if (a == cluster) count++;
      return count;
    }
  }

  private static final int MAX_ROUNDS = 100;
  private static final double EPS = 1e-9;

  private KMeans() {}

  public static Result run(List<Point> points, List<Centre> start) {
    List<Centre> centres = new ArrayList<>(start);
    int[] assignment = new int[points.size()];
    Arrays.fill(assignment, -1);
    boolean ambiguous = false;
    boolean empty = false;
    int rounds = 0;
    while (rounds < MAX_ROUNDS) {
      rounds++;
      int[] next = new int[points.size()];
      for (int i = 0; i < points.size(); i++) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int c = 0; c < centres.size(); c++) {
          double d = Math.hypot(points.get(i).x() - centres.get(c).x(), points.get(i).y() - centres.get(c).y());
          if (Math.abs(d - bestDistance) < EPS) ambiguous = true;
          if (d < bestDistance - EPS) {
            bestDistance = d;
            best = c;
          }
        }
        next[i] = best;
      }
      boolean same = Arrays.equals(next, assignment);
      assignment = next;
      List<Centre> updated = new ArrayList<>();
      for (int c = 0; c < centres.size(); c++) {
        double sumX = 0;
        double sumY = 0;
        int count = 0;
        for (int i = 0; i < points.size(); i++) {
          if (assignment[i] != c) continue;
          sumX += points.get(i).x();
          sumY += points.get(i).y();
          count++;
        }
        if (count == 0) {
          empty = true;
          updated.add(centres.get(c));
        } else {
          updated.add(new Centre(sumX / count, sumY / count));
        }
      }
      centres = updated;
      if (same) break;
    }
    return new Result(assignment, List.copyOf(centres), rounds, ambiguous, empty);
  }
}
