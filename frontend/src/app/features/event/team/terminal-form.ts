import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { message } from "../../../core/api";
import { EventApi } from "../../../core/event/event-api";
import { TerminalFormRequest, TerminalFormView } from "../../../core/event/event-models";
import { isNetworkError, newIdempotencyKey } from "../../../core/event/pending-request";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiConfirmDialog } from "../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiInput } from "../../../shared/ui/ui-input/ui-input";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";

type Field = keyof TerminalFormRequest;
type Action = "save" | "submit";

const EMPTY: TerminalFormRequest = {
  path: "",
  username: "",
  fileSize: "",
  pattern: "",
  finalClue: "",
};
const FIELDS = Object.keys(EMPTY) as Field[];

/** A request that did not reach the server: "Retry" sends the very same Idempotency-Key and values again. */
interface Unconfirmed {
  action: Action;
  key: string;
  body: TerminalFormRequest;
}

/**
 * The team form of a Linux terminal room (rules §18.7), shown next to the terminal: six answers, Save (as often as
 * the team likes, blanks allowed) and Final submit (asks first, irreversible). Everything shown comes from the
 * server, so a refresh or a terminal restart changes nothing; the team only ever sees "Saved" or "Submitted",
 * never whether an answer is right.
 */
@Component({
  selector: "event-terminal-form",
  imports: [FormsModule, UiAlert, UiButton, UiCard, UiConfirmDialog, UiInput, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./terminal-form.css",
  templateUrl: "./terminal-form.html",
})
export class EventTerminalForm {
  private readonly eventApi = inject(EventApi);

  readonly roomId = input.required<number>();
  /** The board's revision: a pushed change (for example the round's end submitting the form) reloads the form. */
  readonly revision = input.required<number>();
  /** The team's round is running on this page's clock; the server has the final word. */
  readonly running = input(false);

  protected readonly view = signal<TerminalFormView | null>(null);
  protected readonly draft = signal<TerminalFormRequest>({ ...EMPTY });
  protected readonly loadError = signal<string | null>(null);
  protected readonly busy = signal<Action | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly unconfirmed = signal<Unconfirmed | null>(null);
  protected readonly confirming = signal(false);

  protected readonly submitted = computed(() => this.view()?.status === "SUBMITTED");
  protected readonly editable = computed(() => this.running() && !this.submitted());
  protected readonly dirty = computed(() => {
    const saved = this.view();
    const draft = this.draft();
    return FIELDS.some((field) => draft[field] !== (saved?.[field] ?? ""));
  });
  protected readonly statusLabel = computed(() => {
    const status = this.view()?.status;
    return status === "SUBMITTED" ? "Submitted" : status === "SAVED" ? "Saved" : "Not saved yet";
  });
  protected readonly chipVariant = computed(() => {
    const status = this.view()?.status;
    return status === "SUBMITTED" ? "success" : status === "SAVED" ? "accent" : "neutral";
  });

  constructor() {
    effect(() => {
      this.roomId();
      this.revision();
      untracked(() => void this.load());
    });
  }

  protected set(field: Field, value: string): void {
    this.draft.update((draft) => ({ ...draft, [field]: value }));
  }

  protected save(): Promise<void> {
    return this.send("save", { ...this.draft() }, newIdempotencyKey());
  }

  protected async confirmSubmit(): Promise<void> {
    this.confirming.set(false);
    await this.send("submit", { ...this.draft() }, newIdempotencyKey());
  }

  protected retry(): Promise<void> {
    const attempt = this.unconfirmed();
    return attempt ? this.send(attempt.action, attempt.body, attempt.key) : Promise.resolve();
  }

  private async send(action: Action, body: TerminalFormRequest, key: string): Promise<void> {
    if (this.busy()) return;
    this.busy.set(action);
    this.error.set(null);
    try {
      const room = this.roomId();
      const saved =
        action === "save"
          ? await this.eventApi.saveTerminalForm(room, body, key)
          : await this.eventApi.submitTerminalForm(room, body, key);
      this.unconfirmed.set(null);
      this.view.set(saved);
      // A Save keeps what the team typed since; a submit shows the locked values.
      if (action === "submit") this.draft.set(valuesOf(saved));
    } catch (e) {
      if (isNetworkError(e)) {
        this.unconfirmed.set({ action, key, body });
        this.error.set("The connection was lost. Press Retry; nothing is saved twice.");
      } else {
        this.unconfirmed.set(null);
        this.error.set(message(e));
        await this.load();
      }
    } finally {
      this.busy.set(null);
    }
  }

  private async load(): Promise<void> {
    try {
      const loaded = await this.eventApi.teamTerminalForm(this.roomId());
      const previous = this.view();
      this.view.set(loaded);
      this.loadError.set(null);
      // First load, a locked form, or nothing typed since the last save: show the server's values.
      if (previous === null || loaded.status === "SUBMITTED" || !this.differsFrom(previous)) this.draft.set(valuesOf(loaded));
    } catch (e) {
      this.loadError.set(message(e));
    }
  }

  private differsFrom(saved: TerminalFormView): boolean {
    const draft = this.draft();
    return FIELDS.some((field) => draft[field] !== saved[field]);
  }
}

function valuesOf(view: TerminalFormView): TerminalFormRequest {
  return {
    path: view.path,
    username: view.username,
    fileSize: view.fileSize,
    pattern: view.pattern,
    finalClue: view.finalClue,
  };
}
