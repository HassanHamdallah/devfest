import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from "@angular/core";
import { RoomActivity, RoomEvidence, TeamRoomCard, TeamRoomStatus } from "../../../core/event/event-models";
import {
  formatTime,
  teamRoomStatusLabel,
} from "../../../core/event/format";
import {
  UiStatusChip,
  UiStatusChipVariant,
} from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { EventTeamEvidenceCard } from "./team-evidence-card";

const STATUS_VARIANTS: Record<TeamRoomStatus, UiStatusChipVariant> = {
  NOT_STARTED: "neutral",
  SUBMITTED: "warning",
  PASSED: "success",
  FAILED: "danger",
};

/**
 * One of the four rooms on the team home: its status, and its clue (with the team's answers) once the room is
 * finished. The room the team is scheduled in right now is highlighted.
 */
@Component({
  selector: "event-team-room-card",
  imports: [EventTeamEvidenceCard, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    "[attr.data-room]": "room().code",
    "[class]": "'room-tone--' + room().code",
    "[class.now]": "room().scheduledNow",
  },
  styles: `
    :host {
      display: grid;
      align-content: start;
      gap: var(--space-2);
      padding: var(--space-4);
      background: var(--color-surface);
      border: 1px solid var(--color-border);
      border-block-start: 6px solid var(--room-color);
      border-radius: var(--radius-card);
    }
    :host(.now) {
      background: var(--room-tint);
      border: var(--border-outline);
      border-block-start: 6px solid var(--room-color);
    }
    h3 {
      margin: 0;
      font-size: var(--font-size-base);
    }
    .detail {
      font-size: var(--font-size-sm);
    }
  `,
  template: `<div class="event-inline">
      <h3>{{ room().name }}</h3>
      @if (room().scheduledNow) {
        <ui-status-chip variant="accent">Now</ui-status-chip>
      }
    </div>
    <span class="detail event-muted">Round {{ room().scheduledRound }}</span>
    <div class="event-inline">
      <ui-status-chip [variant]="statusVariant()">{{
        statusLabel()
      }}</ui-status-chip>
      @if (!evidence()) {
        <ui-status-chip
          [variant]="room().clueCollected ? 'success' : 'neutral'"
          >{{
            activity() === "PHONE_POINTS"
              ? room().clueCollected ? "Retrieved key collected" : "No retrieved key yet"
              : room().clueCollected ? "Clue collected" : "No clue yet"
          }}</ui-status-chip
        >
      }
    </div>
    @if (receiptLine(); as line) {
      <span class="detail event-muted">{{ line }}</span>
    }
    @if (evidence(); as finished) {
      <event-team-evidence-card [evidence]="finished" [recoverable]="recoverable()" />
    }`,
})
export class EventTeamRoomCard {
  readonly room = input.required<TeamRoomCard>();
  /** The room's activity (from the evidence): rooms without an answer form read "Room complete", not "Submitted". */
  readonly activity = input<RoomActivity | null>(null);
  /** The room's evidence once its round is over for this team: the answers and the clue, shown inside the card. */
  readonly evidence = input<RoomEvidence | null>(null);
  /** True when this is the one missing clue a team with 3 clues can still win back in the final. */
  readonly recoverable = input(false);

  protected readonly statusVariant = computed(
    () => STATUS_VARIANTS[this.room().myStatus],
  );
  protected readonly statusLabel = computed(
    () => teamRoomStatusLabel(this.room().myStatus, this.activity()),
  );
  protected readonly receiptLine = computed(() => {
    const receipt = this.room().lastReceipt;
    return receipt ? `Last answer at ${formatTime(receipt.receivedAt)}` : null;
  });
}
