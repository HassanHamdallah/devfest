import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from "@angular/core";
import {
  Receipt,
  RoomCode,
  TeamRoomCard,
} from "../../../core/event/event-models";
import { formatTime } from "../../../core/event/format";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";

interface HistoryEntry {
  id: string;
  roomName: string;
  time: string;
  clueCorrect: boolean;
  updatedAfterReview: boolean;
}

/** The team's own submission receipts, newest first as the server returns them. */
@Component({
  selector: "event-team-answer-history",
  imports: [UiCard, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    ol {
      display: grid;
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    li {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-1) var(--space-3);
      padding-top: var(--space-2);
      border-top: 1px solid var(--color-border);
    }
    code {
      font-size: var(--font-size-xs);
      overflow-wrap: anywhere;
    }
  `,
  template: `<ui-card>
    <h2 ui-card-header>Answer history</h2>
    @if (error(); as text) {
      <p class="event-muted">Could not load the answer history: {{ text }}</p>
    }
    <ol>
      @for (entry of entries(); track entry.id) {
        <li>
          <strong>{{ entry.roomName }}</strong>
          <span class="event-muted">{{ entry.time }}</span>
          <ui-status-chip
            [variant]="entry.clueCorrect ? 'success' : 'danger'"
            >{{ entry.clueCorrect ? "Correct" : "Incorrect" }}</ui-status-chip
          >
          @if (entry.updatedAfterReview) {
            <span class="event-muted">Updated after review</span>
          }
          <code>{{ entry.id }}</code>
        </li>
      } @empty {
        <li class="event-muted">No answers submitted yet.</li>
      }
    </ol>
  </ui-card>`,
})
export class EventTeamAnswerHistory {
  readonly receipts = input.required<readonly Receipt[]>();
  readonly rooms = input.required<readonly TeamRoomCard[]>();
  readonly error = input<string | null>(null);

  protected readonly entries = computed<HistoryEntry[]>(() => {
    const names = new Map<RoomCode, string>(
      this.rooms().map((room) => [room.code, room.name]),
    );
    return this.receipts().map((receipt) => ({
      id: receipt.id,
      roomName: names.get(receipt.roomCode) ?? receipt.roomCode,
      time: formatTime(receipt.receivedAt),
      clueCorrect: receipt.clueCorrect,
      updatedAfterReview: receipt.updatedAfterReview,
    }));
  });
}
