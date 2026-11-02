import { UiStatusChipVariant } from "../../shared/ui/ui-status-chip/ui-status-chip";
import { RoundPhase } from "./event-models";

export interface FinalStatus {
  label: string;
  variant: UiStatusChipVariant;
}

/** Final state from the admin's event state. */
export function finalStatus(state: {
  finalOpen: boolean;
  finalStartedAt: string | null;
}): FinalStatus {
  if (state.finalOpen) return { label: "Final open", variant: "accent" };
  if (state.finalStartedAt !== null)
    return { label: "Final closed", variant: "warning" };
  return { label: "Final not opened", variant: "neutral" };
}

export const PHASE_VARIANTS: Record<RoundPhase, UiStatusChipVariant> = {
  ASSIGNED: "neutral",
  RUNNING: "success",
  PAUSED: "warning",
  TIME_UP: "danger",
};
