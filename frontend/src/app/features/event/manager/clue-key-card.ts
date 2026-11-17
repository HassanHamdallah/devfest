import {
  ChangeDetectionStrategy,
  Component,
  computed,
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
import { ToastService } from "../../../shared/ui/toast/toast.service";
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
  imports: [FormsModule, UiAlert, UiButton, UiInput, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "data-clue-key-card": "" },
  styles: `
    .clue-key {
      display: grid;
      gap: var(--space-3);
      padding: var(--space-3) var(--space-4) var(--space-3) var(--space-5);
      background: var(--color-surface);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-card);
    }
    .clue-key__summary {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2) var(--space-3);
      cursor: pointer;
    }
    .clue-key__body {
      display: grid;
      gap: var(--space-3);
      margin-block-start: var(--space-3);
    }
    .clue-key--missing {
      background: var(--color-warning-subtle);
      border: var(--border-outline-thin);
      border-inline-start: 6px solid var(--brand-yellow);
    }
    .clue-key__line {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2) var(--space-3);
    }
    .clue-key__set {
      font-weight: var(--font-weight-semibold);
      color: var(--color-success);
    }
    .clue-key__value {
      margin: 0;
      font-family: var(--font-mono);
      font-size: var(--font-size-base);
      letter-spacing: 0.04em;
      overflow-wrap: anywhere;
    }
    .clue-key__actions {
      display: flex;
      gap: var(--space-2);
      margin-inline-start: auto;
    }
    .clue-key__form {
      display: grid;
      gap: var(--space-3);
    }
    h2 {
      margin: 0;
      font-size: var(--font-size-base);
    }
    p {
      margin: 0;
    }
    .clue-key__notice:empty {
      display: none;
    }
  `,
  template: `<details
    class="clue-key"
    [class.clue-key--missing]="!room().clueKeySet"
    [open]="isOpen()"
    #box
    (toggle)="expanded.set(box.open)"
    data-clue-key-details
  >
    <summary class="clue-key__summary" (click)="keepOpenWhenMissing($event)">
      <h2 id="clue-key-heading">{{ keyName() }}</h2>
      @if (room().clueKeySet) {
        <span class="clue-key__set">Set ✓</span>
      } @else {
        <span aria-hidden="true">✗</span>
        <ui-status-chip variant="warning"
          >Not set — the room cannot open</ui-status-chip
        >
      }
    </summary>
    <div class="clue-key__body">
    <div class="clue-key__line">
      @if (room().clueKeySet) {
        <p class="clue-key__value" data-clue-key-value>
          {{ revealed() ? room().clueKey : mask }}
        </p>
      }
      @if (!editing()) {
        <div class="clue-key__actions">
          @if (room().clueKeySet) {
            <ui-button
              size="sm"
              variant="ghost"
              [pressed]="revealed()"
              (click)="revealed.set(!revealed())"
              >{{ revealed() ? "Hide" : "Show" }}</ui-button
            >
          }
          @if (room().contentEditable) {
            <ui-button size="sm" variant="secondary" (click)="edit()">{{
              room().clueKeySet ? "Edit" : "Set " + keyName().toLowerCase()
            }}</ui-button>
          }
        </div>
      }
    </div>
    @if (editing()) {
      <form
        class="clue-key__form"
        [attr.aria-label]="'Edit ' + keyName().toLowerCase()"
        (ngSubmit)="save()"
      >
        <p class="event-muted">
          Teams must type this exactly; spaces and letter case are ignored.
          Changing it re-checks every answer already submitted in this room.
        </p>
        <ui-input
          [label]="keyName()"
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
    }
    @if (!room().contentEditable) {
      <ui-alert variant="info"
        >The {{ keyName().toLowerCase() }} is read-only after the final opens.</ui-alert
      >
    }
    <p class="event-muted clue-key__notice" role="status">{{ notice() }}</p>
    @if (error(); as text) {
      <ui-alert variant="error" [dismissible]="true">{{ text }}</ui-alert>
    }
    </div>
  </details>`,
})
export class EventClueKeyCard {
  private readonly eventApi = inject(EventApi);
  private readonly toasts = inject(ToastService);

  readonly room = input.required<RoomBoardRoom>();
  /** The Linux room calls the room's key the "Retrieved key" (the team retrieves it from the server); the others "Clue key". */
  protected readonly keyName = computed(() => (this.room().activity === "LINUX_TERMINAL" ? "Retrieved key" : "Clue key"));
  readonly changed = output<void>();

  /**
   * The card starts open while no key is set and collapsed once one is; after that the manager's own toggling
   * wins (so saving a key never collapses the card under the manager's hands). It can never be closed while the
   * key is missing.
   */
  protected readonly expanded = signal<boolean | null>(null);
  protected readonly isOpen = computed(() => !this.room().clueKeySet || (this.expanded() ?? false));
  protected readonly mask = MASK;
  protected readonly editing = signal(false);
  protected readonly revealed = signal(false);
  protected readonly draft = signal("");
  protected readonly saving = signal(false);
  protected readonly notice = signal("");
  protected readonly error = signal<string | null>(null);

  protected keepOpenWhenMissing(event: Event): void {
    if (!this.room().clueKeySet) event.preventDefault();
  }

  protected edit(): void {
    this.expanded.set(true);
    this.draft.set(this.room().clueKey);
    this.notice.set("");
    this.error.set(null);
    this.editing.set(true);
  }

  /** Saves the clue key; the room's other settings are not part of this request. */
  protected async save(): Promise<void> {
    if (this.saving()) return;
    const room = this.room();
    this.saving.set(true);
    this.error.set(null);
    try {
      const saved = await this.eventApi.saveRoomContent(room.id, {
        clueKey: this.draft(),
        version: room.version,
      });
      this.notice.set(recheckNotice(saved.recheckedSubmissions));
      this.toasts.show(recheckNotice(saved.recheckedSubmissions));
      this.editing.set(false);
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.saving.set(false);
      this.changed.emit();
    }
  }
}
