import { SentinelRun, SentinelStage } from "../../../../core/event/event-models";

/** What each stage kind draws, as sent by GET /team/rooms/{id}/activity (never a point, award or answer). */

export interface Reading {
  id: string;
  label: string;
  x: number;
  y: number;
}
export interface OutlierConfig {
  id: string;
  method: "IQR" | "Z";
  threshold: number;
}
export interface OutlierData {
  readings: Reading[];
  configs: OutlierConfig[];
}

/** The drawing above a CHOICE or INPUT stage (inline SVG): the question's own data, never an answer. */
export interface NeuronFigureData {
  type: "neuron";
  inputs: { label: string; value: number }[];
  weights: number[];
  bias: number;
}
export interface CrossoverFigureData {
  type: "crossover";
  parentA: string;
  parentB: string;
  cut: number;
  mutate: number;
}
export interface SplitFigureData {
  type: "split";
  parent: { normal: number; suspicious: number };
  question: string;
  yes: { normal: number; suspicious: number };
  no: { normal: number; suspicious: number };
}
export interface MinimaxFigureData {
  type: "minimax";
  root: "MAX" | "MIN";
  children: number[][];
}
export type SentinelFigureData = NeuronFigureData | CrossoverFigureData | SplitFigureData | MinimaxFigureData;

/** A multiple choice stage: the options in this team's own order (ids, never positions). */
export interface ChoiceData {
  options: { id: string; text: string }[];
  figure?: SentinelFigureData;
}

/** A typed answer stage: how to type it (never what the answer is) and its drawing. */
export interface InputData {
  inputKind: "TEXT" | "NUMBER";
  maxLength: number;
  figure?: SentinelFigureData;
}

export interface FeatureData {
  target: string;
  features: { id: string; label: string }[];
}

export interface TuneData {
  runsAllowed: number;
  configs: { id: string; label: string }[];
}

export interface Vehicle {
  id: string;
  label: string;
  kind: string;
  length: number;
  height: number;
}
export interface NearestData {
  classes: string[];
  vehicles: Vehicle[];
  unknown: { length: number; height: number };
  ks: number[];
}

export interface CameraData {
  positive: string;
  metrics: string[];
  rows: { actual: boolean; predicted: boolean }[];
}

export interface Dot {
  id: string;
  x: number;
  y: number;
}
export interface Centroid {
  id: string;
  label: string;
  x: number;
  y: number;
}
export interface GroupData {
  dots: Dot[];
  centroids: Centroid[];
  asked: string;
}

export interface Place {
  id: string;
  name: string;
  x: number;
  y: number;
  h: number;
}
export interface Road {
  from: string;
  to: string;
  cost: number;
}
export interface RouteData {
  places: Place[];
  roads: Road[];
  start: string;
  goal: string;
  tieBreak: string;
}

export interface ThreatData {
  message: string[];
  priors: { threat: number; normal: number };
  table: { word: string; threat: number; normal: number }[];
  classes: string[];
}

export interface RuleData {
  facts: { id: string; text: string }[];
  known: string[];
  rules: { id: string; ifAll: string[]; then: string }[];
  conclusions: string[];
}

export interface RagData {
  question: string;
  chunkSizes: string[];
  topKs: number[];
  runsAllowed: number;
  configs: { id: string; chunkSize: string; topK: number }[];
}

/** The stage's data cast to its kind; the server sends exactly the fields above for that kind. */
export function dataOf<T>(stage: SentinelStage): T {
  return (stage.data ?? {}) as T;
}

/** The result the team got for a configuration, or null when it did not run it. */
export function runOf(stage: SentinelStage, config: string): SentinelRun | null {
  return stage.runs.find((run) => run.config === config) ?? null;
}

/** "1.5" for 1.5, "3" for 3. */
export function plain(value: number): string {
  return String(Math.round(value * 1e6) / 1e6);
}
