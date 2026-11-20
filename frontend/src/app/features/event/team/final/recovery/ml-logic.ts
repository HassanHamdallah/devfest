import { RecoveryOption, RecoveryThresholdMetrics } from "../../../../../core/event/event-models";

/** Pure helpers of the ML recovery screens (no DOM, no API), unit tested in ml-logic.spec.ts. */

/** The order after moving the step at {@code index} one place up (-1) or down (+1); the same order at an edge. */
export function movedOrder(order: readonly string[], index: number, delta: -1 | 1): string[] {
  const target = index + delta;
  const next = [...order];
  if (index < 0 || index >= order.length || target < 0 || target >= order.length) return next;
  [next[index], next[target]] = [next[target], next[index]];
  return next;
}

/**
 * The label of a pipeline step in the team's current order: a step the content relabels ("Fit Scaler on Full Dataset" to
 * "Fit Scaler on Training Data") shows its new label once it is after the other step, its own label before.
 */
export function stepLabel(
  item: RecoveryOption,
  order: readonly string[],
  relabel: { item: string; after: string; label: string } | null,
): string {
  if (relabel && item.id === relabel.item && order.indexOf(item.id) > order.indexOf(relabel.after)) return relabel.label;
  return item.label;
}

/** A slider value in whole hundredths, so 0.42 and 0.4200000001 are the same step. */
export function hundredths(value: number): number {
  return Math.round(value * 100);
}

/** The server's metrics at the slider value; null when the value is not one of its steps. */
export function metricsAt(metrics: readonly RecoveryThresholdMetrics[], threshold: number): RecoveryThresholdMetrics | null {
  const wanted = hundredths(threshold);
  return metrics.find((m) => hundredths(m.threshold) === wanted) ?? null;
}

/** "0.84" for 0.84 and "0.90" for 0.9. */
export function twoDecimals(value: number): string {
  return value.toFixed(2);
}
