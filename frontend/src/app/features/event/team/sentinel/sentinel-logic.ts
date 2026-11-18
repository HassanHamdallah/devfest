import { Centroid, Dot, Place, RouteData, Vehicle } from "./sentinel-data";

/**
 * The working state of the interactive stages, as pure functions. They never hold the right answer: they only do
 * what the buttons on screen do (draw the k nearest, assign, update, expand a place, fire a rule), so the team
 * does the reasoning and the server judges the numbers it submits.
 */

// ---- Plot scales ----------------------------------------------------------------------------------

/**
 * What the server accepts as a NUMBER answer: a plain decimal with a point or a comma ("0.19", ".19", "0,19", "4",
 * "-2.5"), no exponent, no text, no thousands separator. null when it is not one. Mirrors the server (it judges).
 */
export function parseNumberAnswer(text: string): number | null {
  const trimmed = text.trim();
  if (!/^[+-]?(?:\d+(?:[.,]\d*)?|[.,]\d+)$/.test(trimmed)) return null;
  const value = Number(trimmed.replace(",", "."));
  return Number.isFinite(value) ? value : null;
}

export interface Plot {
  width: number;
  height: number;
  left: number;
  right: number;
  top: number;
  bottom: number;
  x0: number;
  x1: number;
  y0: number;
  y1: number;
}

/** A plot area that fits the values with a margin, drawn in a fixed 560 × 400 viewBox. */
export function makePlot(xs: number[], ys: number[], pad = 0.08, width = 560, height = 400): Plot {
  const [x0, x1] = niceRange(xs, pad);
  const [y0, y1] = niceRange(ys, pad);
  return { width, height, left: 52, right: 16, top: 14, bottom: 44, x0, x1, y0, y1 };
}

function niceRange(values: number[], pad: number): [number, number] {
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const step = tickStep(span * (1 + 2 * pad), 6);
  return [Math.floor((min - span * pad) / step) * step, Math.ceil((max + span * pad) / step) * step];
}

export function tickStep(span: number, wanted: number): number {
  const raw = span / wanted;
  const power = Math.pow(10, Math.floor(Math.log10(raw)));
  const unit = raw / power;
  return (unit >= 5 ? 5 : unit >= 2 ? 2 : 1) * power;
}

export function ticks(from: number, to: number): number[] {
  const step = tickStep(to - from, 6);
  const out: number[] = [];
  for (let v = Math.ceil(from / step) * step; v <= to + step * 1e-9; v += step) out.push(Math.round(v * 1e6) / 1e6);
  return out;
}

export function sx(plot: Plot, value: number): number {
  return plot.left + ((value - plot.x0) / (plot.x1 - plot.x0)) * (plot.width - plot.left - plot.right);
}

export function sy(plot: Plot, value: number): number {
  return plot.height - plot.bottom - ((value - plot.y0) / (plot.y1 - plot.y0)) * (plot.height - plot.top - plot.bottom);
}

/** Pixels per data unit on each axis (they differ), for drawing a circle of a data radius. */
export function unitsX(plot: Plot): number {
  return (plot.width - plot.left - plot.right) / (plot.x1 - plot.x0);
}

export function unitsY(plot: Plot): number {
  return (plot.height - plot.top - plot.bottom) / (plot.y1 - plot.y0);
}

// ---- Nearest vehicle (stage 4) ----------------------------------------------------------------------

export interface Neighbour {
  vehicle: Vehicle;
  distance: number;
}

/** Nearest first; equal distances keep the order of the ids. */
export function rankVehicles(vehicles: Vehicle[], unknown: { length: number; height: number }): Neighbour[] {
  return vehicles
    .map((vehicle) => ({ vehicle, distance: Math.hypot(vehicle.length - unknown.length, vehicle.height - unknown.height) }))
    .sort((a, b) => a.distance - b.distance || a.vehicle.id.localeCompare(b.vehicle.id));
}

/** The votes of the k nearest, in order of first appearance. */
export function votes(neighbours: Neighbour[], k: number): { kind: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const { vehicle } of neighbours.slice(0, k)) counts.set(vehicle.kind, (counts.get(vehicle.kind) ?? 0) + 1);
  return [...counts].map(([kind, count]) => ({ kind, count }));
}

// ---- K-Means (stage 6) ------------------------------------------------------------------------------

export interface Cluster {
  /** Index of the nearest centroid per dot, or null before the first Assign. */
  assignment: (number | null)[];
  centres: { x: number; y: number }[];
  /** The last button pressed: Update is allowed only after Assign. */
  lastStep: "none" | "assign" | "update";
  /** Assign + Update rounds completed. */
  rounds: number;
  /** The last Update moved nothing. */
  settled: boolean;
}

export function startClusters(dots: Dot[], centroids: Centroid[]): Cluster {
  return {
    assignment: dots.map(() => null),
    centres: centroids.map((c) => ({ x: c.x, y: c.y })),
    lastStep: "none",
    rounds: 0,
    settled: false,
  };
}

export function assign(state: Cluster, dots: Dot[]): Cluster {
  const assignment = dots.map((dot) => {
    let best = 0;
    let bestDistance = Infinity;
    state.centres.forEach((centre, index) => {
      const distance = Math.hypot(dot.x - centre.x, dot.y - centre.y);
      if (distance < bestDistance - 1e-9) {
        best = index;
        bestDistance = distance;
      }
    });
    return best;
  });
  return { ...state, assignment, lastStep: "assign" };
}

export function update(state: Cluster, dots: Dot[]): Cluster {
  const centres = state.centres.map((centre, index) => {
    const mine = dots.filter((_, i) => state.assignment[i] === index);
    if (mine.length === 0) return centre;
    return { x: mine.reduce((sum, d) => sum + d.x, 0) / mine.length, y: mine.reduce((sum, d) => sum + d.y, 0) / mine.length };
  });
  const moved = centres.some((c, i) => Math.hypot(c.x - state.centres[i].x, c.y - state.centres[i].y) > 1e-9);
  return { ...state, centres, lastStep: "update", rounds: state.rounds + 1, settled: !moved };
}

// ---- A* by hand (stage 7) ----------------------------------------------------------------------------

export interface Search {
  /** Places expanded so far, in order. */
  expanded: string[];
  /** Cheapest cost found so far to each place reached. */
  g: Record<string, number>;
  parent: Record<string, string>;
}

export function startSearch(route: RouteData): Search {
  return { expanded: [], g: { [route.start]: 0 }, parent: {} };
}

/** The frontier: reached but not expanded, with f = g + h, lowest f first (equal f: by name). */
export function frontier(route: RouteData, search: Search): { place: Place; g: number; f: number }[] {
  return route.places
    .filter((place) => place.id in search.g && !search.expanded.includes(place.id))
    .map((place) => ({ place, g: search.g[place.id], f: search.g[place.id] + place.h }))
    .sort((a, b) => a.f - b.f || a.place.name.localeCompare(b.place.name));
}

/** Expands a frontier place: its neighbours get a cheaper g when this road is the cheapest found so far. */
export function expand(route: RouteData, search: Search, id: string): Search {
  if (!(id in search.g) || search.expanded.includes(id) || search.expanded.includes(route.goal)) return search;
  const g = { ...search.g };
  const parent = { ...search.parent };
  for (const road of route.roads) {
    const next = road.from === id ? road.to : road.to === id ? road.from : null;
    if (next === null || search.expanded.includes(next)) continue;
    const cost = search.g[id] + road.cost;
    if (!(next in g) || cost < g[next] - 1e-9) {
      g[next] = cost;
      parent[next] = id;
    }
  }
  return { expanded: [...search.expanded, id], g, parent };
}

/** The route the team's search found to the goal (empty until the goal is expanded) and its cost. */
export function foundRoute(route: RouteData, search: Search): { path: string[]; cost: number } {
  if (!search.expanded.includes(route.goal)) return { path: [], cost: 0 };
  const path = [route.goal];
  while (search.parent[path[0]] !== undefined) path.unshift(search.parent[path[0]]);
  return { path, cost: search.g[route.goal] };
}

// ---- Rule engine (stage 9) -----------------------------------------------------------------------------

/** What clicking a rule does: nothing new when its result is known already, a refusal when a condition is missing. */
export function fireRule(known: string[], rule: { ifAll: string[]; then: string }): "added" | "known" | "blocked" {
  if (!rule.ifAll.every((fact) => known.includes(fact))) return "blocked";
  return known.includes(rule.then) ? "known" : "added";
}
