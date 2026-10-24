import {
  AdminContent,
  RoomContentView,
  SuspectView,
} from "../../../../core/event/event-models";
import { UiSelectOption } from "../../../../shared/ui/ui-select/ui-select";
import { UiStatusChipVariant } from "../../../../shared/ui/ui-status-chip/ui-status-chip";

export const REQUIRED_SUSPECTS = 6;
export const NO_SUSPECT = "";
export const READ_ONLY_REASON =
  "Event content is read-only after the final opens.";

export interface CountChip {
  text: string;
  variant: UiStatusChipVariant;
}

export function suspectCount(content: AdminContent): CountChip {
  const count = content.suspects.length;
  return {
    text: `${count} / ${REQUIRED_SUSPECTS}`,
    variant: count === REQUIRED_SUSPECTS ? "success" : "warning",
  };
}

export function correctSuspectOptions(content: AdminContent): UiSelectOption[] {
  return [
    { value: NO_SUSPECT, label: "No correct suspect" },
    ...content.suspects.map((suspect) => ({
      value: String(suspect.id),
      label: suspect.name,
    })),
  ];
}

/** Why "Add suspect" is unavailable, or null. */
export function addSuspectBlockReason(content: AdminContent): string | null {
  if (content.readOnly) return READ_ONLY_REASON;
  return content.suspects.length >= REQUIRED_SUSPECTS
    ? `All ${REQUIRED_SUSPECTS} suspects are defined.`
    : null;
}

/** Why a suspect cannot be deleted, or null. Accused suspects are rejected by the server. */
export function deleteSuspectBlockReason(
  suspect: SuspectView,
  content: AdminContent,
): string | null {
  if (content.readOnly) return READ_ONLY_REASON;
  return suspect.id === content.correctSuspectId
    ? "This is the correct suspect. Choose another one before deleting it."
    : null;
}

/** "Ready", or what the room still needs before the final can open. */
export function roomContentStatus(room: RoomContentView): CountChip {
  const missing = [
    room.clueKey.trim() ? null : "clue key",
    room.recoveryPrompt.trim() && room.recoveryAnswer.trim()
      ? null
      : "recovery challenge",
  ].filter((item): item is string => item !== null);
  return missing.length
    ? { text: `Needs ${missing.join(" and ")}`, variant: "warning" }
    : { text: "Ready", variant: "success" };
}
