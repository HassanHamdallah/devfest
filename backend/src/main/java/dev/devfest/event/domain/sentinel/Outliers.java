package dev.devfest.event.domain.sentinel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Outlier Hunt detectors (stage 1), derived from the readings so no author can mistype a flagged list. Each axis
 * (east x, north y) is judged on its own and a point is flagged when it is outside on x OR on y. Pure.
 *
 * <ul>
 *   <li><b>IQR</b> with factor k: Tukey's hinges per axis (Q1 and Q3 are the medians of the lower and the upper half
 *       of the sorted values; with an odd count the median belongs to both halves); fences Q1 - k*IQR and
 *       Q3 + k*IQR; outside a fence (strictly) is flagged.</li>
 *   <li><b>Z-score</b> with threshold t: population standard deviation (divide by n) per axis; |x - mean| / sd above
 *       t is flagged. An axis whose values are all equal flags nothing.</li>
 * </ul>
 */
public final class Outliers {

  public record Point(String id, double x, double y) {}

  private Outliers() {}

  /** The ids flagged by IQR fences of factor {@code k}, in the order of the points. */
  public static List<String> iqr(List<Point> points, double k) {
    double[] xs = axis(points, true);
    double[] ys = axis(points, false);
    double[] fx = fences(xs, k);
    double[] fy = fences(ys, k);
    List<String> flagged = new ArrayList<>();
    for (Point point : points) {
      if (point.x() < fx[0] || point.x() > fx[1] || point.y() < fy[0] || point.y() > fy[1]) flagged.add(point.id());
    }
    return flagged;
  }

  /** The ids flagged by a Z-score above {@code threshold} on either axis, in the order of the points. */
  public static List<String> zScore(List<Point> points, double threshold) {
    double[] xs = axis(points, true);
    double[] ys = axis(points, false);
    List<String> flagged = new ArrayList<>();
    for (Point point : points) {
      if (z(xs, point.x()) > threshold || z(ys, point.y()) > threshold) flagged.add(point.id());
    }
    return flagged;
  }

  /** Tukey's hinges {Q1, Q3} of the values. */
  public static double[] hinges(double[] values) {
    double[] sorted = values.clone();
    Arrays.sort(sorted);
    int n = sorted.length;
    int half = n / 2;
    int lowerEnd = n % 2 == 0 ? half : half + 1;
    return new double[] {median(sorted, 0, lowerEnd), median(sorted, n - lowerEnd, n)};
  }

  /** The lower and upper fence of one axis. */
  public static double[] fences(double[] values, double k) {
    double[] h = hinges(values);
    double iqr = h[1] - h[0];
    return new double[] {h[0] - k * iqr, h[1] + k * iqr};
  }

  public static double mean(double[] values) {
    return Arrays.stream(values).average().orElse(0);
  }

  /** The population standard deviation (divide by n). */
  public static double populationSd(double[] values) {
    double mean = mean(values);
    return Math.sqrt(Arrays.stream(values).map(v -> (v - mean) * (v - mean)).average().orElse(0));
  }

  public static double[] axis(List<Point> points, boolean x) {
    return points.stream().mapToDouble(p -> x ? p.x() : p.y()).toArray();
  }

  /**
   * How far the closest point is from deciding the other way: the smallest distance of any value to an IQR fence
   * (in data units), or of any |z| to the threshold. A file whose margin is tiny would flag different points under
   * a different rounding, so the content rules refuse it.
   */
  public static double margin(List<Point> points, boolean iqr, double factor) {
    double margin = Double.MAX_VALUE;
    for (boolean x : new boolean[] {true, false}) {
      double[] values = axis(points, x);
      if (iqr) {
        double[] fence = fences(values, factor);
        for (double v : values) margin = Math.min(margin, Math.min(Math.abs(v - fence[0]), Math.abs(v - fence[1])));
      } else if (populationSd(values) > 0) {
        for (double v : values) margin = Math.min(margin, Math.abs(z(values, v) - factor));
      }
    }
    return margin;
  }

  private static double z(double[] values, double value) {
    double sd = populationSd(values);
    return sd == 0 ? 0 : Math.abs(value - mean(values)) / sd;
  }

  private static double median(double[] sorted, int from, int to) {
    int count = to - from;
    int mid = from + count / 2;
    return count % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
  }
}
