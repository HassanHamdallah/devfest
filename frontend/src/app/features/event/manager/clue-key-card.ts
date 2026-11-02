import {
  ChangeDetectionStrategy,
  Component,
  inject,
  input,
  output,
  signal,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { message } from "../../../core/api";
import { EventApi } from "../../../core/event/event-api";
import { RoomBoardRoom } from "../../../core/event/event-models";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiInput } from "../../../shared/ui/ui-input/ui-input";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";

/** Shown instead of the key until the manager presses Show; a fixed length reveals nothing about the key. */
const MASK = "••••••••";

export function recheckNotice(count: number): string {
  return `Saved. Re-checked ${count} ${count === 1 ? "answer" : "answers"}.`;
}

/**
 * The room's clue key, owned by the room's manager at runtime. Empty means the room cannot open. Saving a
 * different key re-checks every answer already submitted here; read-only once the final opens.
 */
@Component({
  selector: "event-clue-key-card",
  imports: [FormsModule, UiAlert, UiButton, UiCard, UiInput, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "data-clue-key-card": "" },
  styles: `
    .clue-key {
      display: grid;
      gap: var(--space-3);
    }
    .clue-key__value {
      margin: 0;
      font-family: var(--font-mono, monospace);
      font-size: var(--font-size-lg);
      overflow-wrap: anywhere;
    }
    .clue-key__header {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
    }
    h2 {
      margin: 0;
    }
    p {
      margin: 0;
    }
  `,
  template: `<ui-card>
    <div ui-card-header class="clue-key__header">
      <h2>Clue key</h2>
      @if (!room().clueKeySet) {
        <ui-status-chip variant="warning"
          >Not set — the room cannot open</ui-status-chip
        >
      }
    </div>
    <div class="clue-key">
      <p class="event-muted">
        Teams must type this exactly; spaces and letter case are ignored.
        Changing it re-checks every answer already submitted in this room.
      </p>
      @if (editing()) {
        <form
          class="clue-key"
          aria-label="Edit clue key"
          (ngSubmit)="save()"
        >
          <ui-input
            label="Clue key"
            name="clueKey"
            autocomplete="off"
            [ngModel]="draft()"
            (ngModelChange)="draft.set($event)"
          />
          <div class="event-inline">
            <ui-button type="submit" [loading]="saving()">Save key</ui-button>
            <ui-button
              variant="ghost"
              [disabled]="saving()"
              (click)="editing.set(false)"
              >Cancel</ui-button
            >
          </div>
        </form>
      } @else {
        @if (room().clueKeySet) {
          <p class="clue-key__value" data-clue-key-value>
            {{ revealed() ? room().clueKey : mask }}
          </p>
        }
        <div class="event-inline">
          @if (room().clueKeySet) {
            <ui-button
              size="sm"
              variant="secondary"
              [pressed]="revealed()"
              (click)="revealed.set(!revealed())"
              >{{ revealed() ? "Hide" : "Show" }}</ui-button
            >
          }
          @if (room().contentEditable) {
            <ui-button size="sm" variant="secondary" (click)="edit()">{{
              room().clueKeySet ? "Edit" : "Set clue key"
            }}</ui-button>
          }
        </div>
      }
      @if (!room().contentEditable) {
        <ui-alert variant="info"
          >The clue key is read-only after the final opens.</ui-alert
        >
      }
      <p class="event-muted" role="status">{{ notice() }}</p>
      @if (error(); as text) {
        <ui-alert variant="error">{{ text }}</ui-alert>
      }
    </div>
  </ui-card>`,
})
export class EventClueKeyCard {
  private readonly eventApi = inject(EventApi);

  readonly room = input.required<RoomBoardRoom>();
  readonly changed = output<void>();

  protected readonly mask = MASK;
  protected readonly editing = signal(false);
  protected readonly revealed = signal(false);
  protected readonly draft = signal("");
  protected readonly saving = signal(false);
  protected readonly notice = signal("");
  protected readonly error = signal<string | null>(null);

  protected edit(): void {
    this.draft.set(this.room().clueKey);
    this.notice.set("");
    this.error.set(null);
    this.editing.set(true);
  }

  /** Sends the room's current recovery challenge unchanged; only the key is edited here. */
  protected async save(): Promise<void> {
    if (this.saving()) return;
    const room = this.room();
    this.saving.set(true);
    this.error.set(null);
    try {
      const saved = await this.eventApi.saveRoomContent(room.id, {
        clueKey: this.draft(),
        recoveryPrompt: room.recoveryPrompt,
        recoveryAnswer: room.recoveryAnswer,
        version: room.version,
      });
      this.notice.set(recheckNotice(saved.recheckedSubmissions));
      this.editing.set(false);
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.saving.set(false);
      this.changed.emit();
    }
  }
}
