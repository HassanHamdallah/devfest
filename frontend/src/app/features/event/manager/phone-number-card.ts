import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { message } from "../../../core/api";
import { EventApi } from "../../../core/event/event-api";
import { RoomBoardRoom } from "../../../core/event/event-models";
import { ToastService } from "../../../shared/ui/toast/toast.service";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiInput } from "../../../shared/ui/ui-input/ui-input";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";

const MASK = "••••••••";
const MAX_CHARACTERS = 40;

/**
 * The phone number teams must find in the points-and-phone room (rules §21.3), set by the room's manager or the admin
 * next to the room's Retrieved key. A team that types the same digits (spaces, dashes and a leading + do not matter)
 * gets the key automatically. The number is never sent to teams and never written to the audit log; read-only after
 * the final opens.
 */
@Component({
  selector: "event-phone-number-card",
  imports: [FormsModule, UiAlert, UiButton, UiInput, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "data-phone-number-card": "" },
  styles: `
    .phone {
      display: grid;
      gap: var(--space-3);
      padding: var(--space-3) var(--space-4) var(--space-3) var(--space-5);
      background: var(--color-surface);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-card);
    }
    .phone--missing {
      background: var(--color-warning-subtle);
      border: var(--border-outline-thin);
      border-inline-start: 6px solid var(--brand-yellow);
    }
    .phone__head,
    .phone__line {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2) var(--space-3);
    }
    .phone__value {
      margin: 0;
      font-family: var(--font-mono);
      letter-spacing: 0.04em;
      overflow-wrap: anywhere;
    }
    .phone__actions {
      display: flex;
      gap: var(--space-2);
      margin-inline-start: auto;
    }
    .phone__form {
      display: grid;
      gap: var(--space-3);
    }
    h2,
    p {
      margin: 0;
    }
    h2 {
      font-size: var(--font-size-base);
    }
  `,
  template: `<section class="phone" [class.phone--missing]="!room().phoneNumberSet" aria-labelledby="phone-number-heading">
    <div class="phone__head">
      <h2 id="phone-number-heading">Phone number (answer)</h2>
      @if (room().phoneNumberSet) {
        <span data-phone-set>Set ✓</span>
      } @else {
        <ui-status-chip variant="warning">Not set — teams cannot get the key</ui-status-chip>
      }
    </div>
    <p class="event-muted">
      Teams type this number to receive the room's Retrieved key. Spaces, dashes and a leading + are ignored; only the digits
      are compared. Teams never see it.
    </p>
    @if (room().phoneNumberSet && !editing()) {
      <div class="phone__line">
        <p class="phone__value" data-phone-value>{{ revealed() ? room().phoneNumber : mask }}</p>
        <div class="phone__actions">
          <ui-button size="sm" variant="ghost" [pressed]="revealed()" (click)="revealed.set(!revealed())">{{
            revealed() ? "Hide" : "Show"
          }}</ui-button>
          @if (room().contentEditable) {
            <ui-button size="sm" variant="secondary" (click)="edit()">Edit</ui-button>
          }
        </div>
      </div>
    }
    @if (!room().phoneNumberSet && !editing() && room().contentEditable) {
      <div><ui-button size="sm" variant="secondary" (click)="edit()">Set phone number</ui-button></div>
    }
    @if (editing()) {
      <form class="phone__form" aria-label="Edit phone number" (ngSubmit)="save()">
        <ui-input
          label="Phone number (answer)"
          name="phoneNumber"
          autocomplete="off"
          [hint]="'Up to ${MAX_CHARACTERS} characters, for example +970-59-881.'"
          [ngModel]="draft()"
          (ngModelChange)="draft.set($event)"
        />
        <div class="event-inline">
          <ui-button type="submit" [loading]="saving()">Save phone number</ui-button>
          <ui-button variant="ghost" [disabled]="saving()" (click)="editing.set(false)">Cancel</ui-button>
        </div>
      </form>
    }
    @if (!room().contentEditable) {
      <ui-alert variant="info">The phone number is read-only after the final opens.</ui-alert>
    }
    @if (error(); as text) {
      <ui-alert variant="error" [dismissible]="true" (dismissed)="error.set(null)">{{ text }}</ui-alert>
    }
  </section>`,
})
export class EventPhoneNumberCard {
  private readonly eventApi = inject(EventApi);
  private readonly toasts = inject(ToastService);

  readonly room = input.required<RoomBoardRoom>();
  readonly changed = output<void>();

  protected readonly mask = MASK;
  protected readonly editing = signal(false);
  protected readonly revealed = signal(false);
  protected readonly draft = signal("");
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly editable = computed(() => this.room().contentEditable);

  protected edit(): void {
    this.draft.set(this.room().phoneNumber);
    this.error.set(null);
    this.editing.set(true);
  }

  protected async save(): Promise<void> {
    if (this.saving()) return;
    const text = this.draft().trim();
    if (text.length > MAX_CHARACTERS) {
      this.error.set(`The phone number can have at most ${MAX_CHARACTERS} characters.`);
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    try {
      await this.eventApi.savePhoneNumber(this.room().id, text);
      this.toasts.show(text ? "Phone number saved." : "Phone number cleared.");
      this.editing.set(false);
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.saving.set(false);
      this.changed.emit();
    }
  }
}
