import { RoomContentView } from "../../../../core/event/event-models";
import { UiStatusChipVariant } from "../../../../shared/ui/ui-status-chip/ui-status-chip";

export const READ_ONLY_REASON =
  "Event content is read-only after the final opens.";

export interface CountChip {
  text: string;
  variant: UiStatusChipVariant;
}

/** "Ready", or what the room still needs before the final can open. */
export function roomContentStatus(room: RoomContentView): CountChip {
  return room.clueKey.trim()
    ? { text: "Ready", variant: "success" }
    : { text: "Needs clue key", variant: "warning" };
}
