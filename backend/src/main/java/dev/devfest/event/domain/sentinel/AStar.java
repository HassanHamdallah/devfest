package dev.devfest.event.domain.sentinel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A* and greedy best-first search on a small undirected graph (stage 7). Pure. The tie rule shown to teams is fixed
 * here: among equal priority, expand the node whose name comes first alphabetically (then the lower id).
 */
public final class AStar {

  public record Node(String id, String name, double h) {}

  public record Edge(String from, String to, double cost) {}

  /** @param order the nodes in the order they were expanded, ending with the goal */
  public record Result(List<String> order, List<String> path, double cost) {}

  private static final double EPS = 1e-9;

  private final Map<String, Node> nodes = new LinkedHashMap<>();
  private final Map<String, List<Edge>> adjacent = new HashMap<>();

  public AStar(List<Node> nodes, List<Edge> edges) {
    for (Node node : nodes) {
      this.nodes.put(node.id(), node);
      adjacent.put(node.id(), new ArrayList<>());
    }
    for (Edge edge : edges) {
      adjacent.get(edge.from()).add(edge);
      adjacent.get(edge.to()).add(new Edge(edge.to(), edge.from(), edge.cost()));
    }
  }

  /** Expands the lowest f = g + h; null when the goal cannot be reached. */
  public Result aStar(String start, String goal) {
    return search(start, goal, true);
  }

  /** Expands the lowest h only, ignoring the cost so far; null when the goal cannot be reached. */
  public Result greedy(String start, String goal) {
    return search(start, goal, false);
  }

  /**
   * True when, at any expansion of A*, two places on the frontier had the same f (so the tie-break would decide the
   * order). Content for the stage is written so this never happens.
   */
  public boolean aStarHasFrontierTie(String start, String goal) {
    boolean[] tied = {false};
    search(start, goal, true, tied);
    return tied[0];
  }

  private Result search(String start, String goal, boolean useCost) {
    return search(start, goal, useCost, new boolean[1]);
  }

  private Result search(String start, String goal, boolean useCost, boolean[] tied) {
    Map<String, Double> g = new HashMap<>();
    Map<String, String> parent = new HashMap<>();
    Set<String> open = new HashSet<>();
    Set<String> closed = new HashSet<>();
    List<String> order = new ArrayList<>();
    g.put(start, 0.0);
    open.add(start);
    Comparator<String> byPriority = (a, b) -> {
      double fa = (useCost ? g.get(a) : 0) + nodes.get(a).h();
      double fb = (useCost ? g.get(b) : 0) + nodes.get(b).h();
      if (Math.abs(fa - fb) > EPS) return Double.compare(fa, fb);
      int byName = nodes.get(a).name().compareTo(nodes.get(b).name());
      return byName != 0 ? byName : a.compareTo(b);
    };
    while (!open.isEmpty()) {
      String current = open.stream().min(byPriority).orElseThrow();
      if (useCost) {
        for (String a : open) {
          for (String b : open) {
            if (a.compareTo(b) < 0 && Math.abs(g.get(a) + nodes.get(a).h() - g.get(b) - nodes.get(b).h()) <= EPS) tied[0] = true;
          }
        }
      }
      open.remove(current);
      closed.add(current);
      order.add(current);
      if (current.equals(goal)) {
        List<String> path = new ArrayList<>();
        for (String at = goal; at != null; at = parent.get(at)) path.add(at);
        Collections.reverse(path);
        return new Result(List.copyOf(order), List.copyOf(path), g.get(goal));
      }
      for (Edge edge : adjacent.get(current)) {
        double through = g.get(current) + edge.cost();
        boolean better = !g.containsKey(edge.to()) || through < g.get(edge.to()) - EPS;
        if (closed.contains(edge.to()) && useCost && better) closed.remove(edge.to());
        if (!closed.contains(edge.to()) && better) {
          g.put(edge.to(), through);
          parent.put(edge.to(), current);
          open.add(edge.to());
        }
      }
    }
    return null;
  }

  /** The true cheapest cost from every reachable node to {@code goal} (Dijkstra), for checking that h never overestimates. */
  public Map<String, Double> costsTo(String goal) {
    Map<String, Double> best = new HashMap<>();
    best.put(goal, 0.0);
    Set<String> done = new HashSet<>();
    while (true) {
      String current = null;
      for (String id : best.keySet()) {
        if (!done.contains(id) && (current == null || best.get(id) < best.get(current))) current = id;
      }
      if (current == null) return best;
      done.add(current);
      for (Edge edge : adjacent.get(current)) {
        double through = best.get(current) + edge.cost();
        if (!best.containsKey(edge.to()) || through < best.get(edge.to())) best.put(edge.to(), through);
      }
    }
  }

  /** Every edge in both directions, for the consistency check h(u) <= cost(u,v) + h(v). */
  public List<Edge> directedEdges() {
    List<Edge> all = new ArrayList<>();
    adjacent.values().forEach(all::addAll);
    return all;
  }

  public Node node(String id) {
    return nodes.get(id);
  }
}
