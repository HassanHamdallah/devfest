package dev.devfest.event.domain.sentinel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** k-nearest-neighbours on a plane (stage 4). Pure; distances are Euclidean on the raw coordinates. */
public final class Knn {

  public record Labelled(String id, String kind, double x, double y) {}

  public record Neighbor(Labelled point, double distance) {}

  private Knn() {}

  /** Every labelled point, nearest first; equal distances keep the order of their ids. */
  public static List<Neighbor> ranked(List<Labelled> labelled, double x, double y) {
    List<Neighbor> all = new ArrayList<>();
    for (Labelled point : labelled) all.add(new Neighbor(point, Math.hypot(point.x() - x, point.y() - y)));
    all.sort(Comparator.comparingDouble(Neighbor::distance).thenComparing(n -> n.point().id()));
    return all;
  }

  /**
   * The majority class among the k nearest. A tied vote goes to the tied class whose nearest member is closest, so
   * the answer is always defined (the content check refuses a fixture that relies on it).
   */
  public static String classify(List<Labelled> labelled, double x, double y, int k) {
    List<Neighbor> nearest = ranked(labelled, x, y).subList(0, Math.min(k, labelled.size()));
    Map<String, Integer> votes = votes(nearest);
    int best = votes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    for (Neighbor neighbor : nearest) {
      if (votes.get(neighbor.point().kind()) == best) return neighbor.point().kind();
    }
    throw new IllegalStateException("No neighbours");
  }

  /** True when the k-th and (k+1)-th neighbours are equally far (the set of k neighbours would be ambiguous). */
  public static boolean boundaryTie(List<Labelled> labelled, double x, double y, int k) {
    List<Neighbor> all = ranked(labelled, x, y);
    return k < all.size() && Math.abs(all.get(k - 1).distance() - all.get(k).distance()) < 1e-9;
  }

  /** True when the k nearest neighbours vote with a tie for first place. */
  public static boolean voteTie(List<Labelled> labelled, double x, double y, int k) {
    Map<String, Integer> votes = votes(ranked(labelled, x, y).subList(0, Math.min(k, labelled.size())));
    int best = votes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    return votes.values().stream().filter(count -> count == best).count() > 1;
  }

  private static Map<String, Integer> votes(List<Neighbor> nearest) {
    Map<String, Integer> votes = new LinkedHashMap<>();
    for (Neighbor neighbor : nearest) votes.merge(neighbor.point().kind(), 1, Integer::sum);
    return votes;
  }
}
