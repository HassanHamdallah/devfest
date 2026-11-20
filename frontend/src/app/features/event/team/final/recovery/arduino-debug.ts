import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, linkedSignal, output, signal, untracked } from "@angular/core";
import { Api } from "../../../../../core/api";
import { RECOVERY_ARDUINO_PATH } from "../../../../../core/event/event-api";
import { ArduinoRunRequest, ArduinoView, RecoveryCheckResult } from "../../../../../core/event/event-models";
import { UiAlert } from "../../../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../../../shared/ui/ui-card/ui-card";
import { UiConfirmDialog } from "../../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { EventAttemptStatus } from "../../attempt-status";
import { PendingAttempt } from "../../pending-attempt";
import { FinalWait } from "../final-wait";
import { ArduinoDraft, clearDraft, isOriginal, readDraft, writeDraft } from "./arduino-draft";
import { EventArduinoEditor } from "./arduino-editor";

/** The Arduino runs come at most once every 3 seconds per team (the server's limit); the button waits that long too. */
const RUN_SECONDS = 3;

type TestStatus = "PASS" | "FAIL" | "NOT RUN";

interface TestRow {
  id: string;
  name: string;
  status: TestStatus;
}

interface LastRun {
  passed: boolean;
  message: string;
  token: string | null;
}

/**
 * The Arduino Debugging level: the expected behaviour and the brief, the program in the editor, the hidden tests and their
 * PASS / FAIL. The program and its texts come from the server; the team's edits stay in sessionStorage until a run, so a
 * refresh keeps them. A run sends every line and the chosen option; the server alone decides which tests pass.
 */
@Component({
  selector: "event-arduino-debug",
  imports: [EventArduinoEditor, EventAttemptStatus, UiAlert, UiButton, UiCard, UiConfirmDialog],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      gap: var(--space-4);
      min-inline-size: 0;
    }
    ui-card {
      min-inline-size: 0;
    }
    .head {
      display: grid;
      gap: var(--space-1);
    }
    .head p {
      margin: 0;
    }
    .badge {
      justify-self: start;
      padding: 2px var(--space-3);
      font-size: var(--font-size-xs);
      font-weight: var(--font-weight-bold);
      letter-spacing: 0.06em;
      color: var(--color-primary-text);
      background: var(--color-primary-subtle);
      border-radius: var(--radius-full);
    }
    .pair {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: var(--space-4);
    }
    @media (min-width: 760px) {
      .pair {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
    .pair h3 {
      margin: 0 0 var(--space-2);
      font-size: var(--font-size-lg);
    }
    .pair p {
      margin: 0 0 var(--space-3);
    }
    ul {
      display: grid;
      gap: var(--space-2);
      margin: 0;
      padding-inline-start: var(--space-5);
    }
    .file {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
      margin-block-end: var(--space-3);
    }
    .file code {
      font-family: var(--font-mono);
      font-weight: var(--font-weight-semibold);
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-3);
      margin-block-start: var(--space-4);
    }
    .actions ui-button:first-child {
      flex: 1 1 220px;
    }
    .hint {
      margin: var(--space-2) 0 0;
      font-size: var(--font-size-sm);
    }
    .tests {
      display: grid;
      gap: var(--space-2);
      margin: var(--space-4) 0 0;
      padding: 0;
      list-style: none;
    }
    .tests li {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3);
      min-block-size: 44px;
      padding: 0 var(--space-4);
      background: var(--color-surface);
      border: var(--border-outline-thin);
      border-radius: var(--radius-md);
    }
    .tests .status {
      flex: none;
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-bold);
      font-variant-numeric: tabular-nums;
      color: var(--color-text-muted);
    }
    .tests .pass {
      background: var(--color-success-subtle);
      border-color: var(--brand-green-ink);
    }
    .tests .pass .status {
      color: var(--brand-green-ink);
    }
    .tests .fail {
      background: var(--color-error-subtle);
      border-color: var(--color-error);
    }
    .tests .fail .status {
      color: var(--color-error);
    }
    .token {
      margin: var(--space-4) 0 0;
      font-weight: var(--font-weight-bold);
      color: var(--brand-green-ink);
    }
    .token code {
      font-family: var(--font-mono);
    }
    ui-alert {
      display: block;
      margin-block-start: var(--space-4);
    }
  `,
  template: `<ui-card data-recovery-level="ARDUINO">
      <div class="head">
        <h2 ui-card-header>{{ program().title }}</h2>
        <p class="event-muted">{{ program().intro }}</p>
        <span class="badge">{{ program().badge }}</span>
      </div>
    </ui-card>
    <div class="pair">
      <ui-card data-arduino-expected>
        <h3>Expected behaviour</h3>
        <ul>
          @for (line of program().expectedBehaviour; track $index) {
            <li>{{ line }}</li>
          }
        </ul>
      </ui-card>
      <ui-card data-arduino-brief>
        <h3>Challenge brief</h3>
        <p>{{ program().brief }}</p>
        <ui-alert variant="warning">{{ program().warning }}</ui-alert>
      </ui-card>
    </div>
    <ui-card>
      <div class="file">
        <code data-arduino-file>{{ program().fileName }}</code>
        <span class="event-muted">Edit any line. Lines cannot be added or removed.</span>
      </div>
      <event-arduino-editor
        [program]="program()"
        [lines]="draft().lines"
        [option]="draft().option"
        (lineChange)="editLine($event.index, $event.value)"
        (optionChange)="chooseOption($event)"
      />
      <div class="actions">
        <ui-button
          size="lg"
          [loading]="attempt.sending()"
          [disabled]="draft().option === null || wait.left() > 0"
          (click)="run()"
          data-arduino-run
          >{{ wait.left() > 0 ? "Wait " + wait.left() + " s" : program().action }}</ui-button
        >
        <ui-button variant="secondary" size="lg" [disabled]="attempt.sending()" (click)="confirmingReset.set(true)" data-arduino-reset
          >Reset code</ui-button
        >
      </div>
      @if (draft().option === null) {
        <p class="hint event-muted">Choose the missing behaviour in the program before running the tests.</p>
      }
      <event-attempt-status [attempt]="attempt" (retry)="retry()" />
      @if (last(); as result) {
        <ui-alert [variant]="result.passed ? 'success' : 'error'" data-recovery-result [attr.data-passed]="result.passed">{{ result.message }}</ui-alert>
      }
      <ul class="tests" aria-label="Hidden tests" data-arduino-tests>
        @for (row of rows(); track row.id) {
          <li [class.pass]="row.status === 'PASS'" [class.fail]="row.status === 'FAIL'" [attr.data-test]="row.id" [attr.data-status]="row.status">
            <span>{{ row.name }}</span>
            <span class="status">{{ row.status }}</span>
          </li>
        }
      </ul>
      @if (last()?.token; as token) {
        <p class="token" data-recovery-token="ARDUINO">RECOVERY TOKEN: <code>{{ token }}</code></p>
      }
    </ui-card>
    <ui-confirm-dialog
      [open]="confirmingReset()"
      title="Reset the code?"
      message="Every edit is replaced by the original program, and the chosen missing behaviour is cleared."
      confirmLabel="Reset code"
      confirmVariant="danger"
      (confirmed)="reset()"
      (cancelled)="confirmingReset.set(false)"
    />`,
})
export class EventArduinoDebug {
  private readonly userId = inject(Api).user()?.id ?? null;

  readonly program = input.required<ArduinoView>();
  /** The latest run's PASS / FAIL as the server remembers it (empty before the first run). */
  readonly lastTests = input<readonly { id: string; passed: boolean }[]>([]);
  /** The server's own count of the seconds still to wait (the page may have been reloaded inside the limit). */
  readonly waitSeconds = input(0);
  /** A run was answered: the page reloads, which moves the team to the unlock screen once the level is passed. */
  readonly changed = output<void>();

  protected readonly attempt = new PendingAttempt<ArduinoRunRequest, RecoveryCheckResult>("recovery");
  protected readonly wait = new FinalWait(inject(DestroyRef));
  protected readonly last = signal<LastRun | null>(null);
  protected readonly confirmingReset = signal(false);
  /** The tests of the run just made; null until then, so the screen shows what the server remembers. */
  private readonly results = signal<readonly { id: string; passed: boolean }[] | null>(null);

  private readonly original = computed(() => this.program().lines.map((line) => line.code));
  private readonly letters = computed(() => this.program().slot.options.map((choice) => choice.letter));
  /** Changes only when the program itself does, never on a reload of the same program: the edits survive a reload. */
  private readonly programKey = computed(() => JSON.stringify([this.original(), this.letters()]));

  /** The team's edits and chosen option: its saved draft if it still fits this program, the original otherwise. */
  protected readonly draft = linkedSignal<string, ArduinoDraft>({
    source: this.programKey,
    computation: () =>
      untracked(() => readDraft(this.userId, this.original().length, this.letters())) ?? { lines: [...untracked(this.original)], option: null },
  });

  protected readonly rows = computed<TestRow[]>(() => {
    const shown = this.results() ?? this.lastTests();
    const byId = new Map(shown.map((test) => [test.id, test.passed]));
    return this.program().tests.map((test) => ({
      id: test.id,
      name: test.name,
      status: byId.has(test.id) ? (byId.get(test.id) ? "PASS" : "FAIL") : "NOT RUN",
    }));
  });

  constructor() {
    // Untracked: set() reads the countdown itself, and every tick must not restart it.
    effect(() => {
      const seconds = this.waitSeconds();
      untracked(() => this.wait.set(seconds));
    });
  }

  protected editLine(index: number, value: string): void {
    this.setDraft({ ...this.draft(), lines: this.draft().lines.map((line, i) => (i === index ? value : line)) });
  }

  protected chooseOption(option: string | null): void {
    this.setDraft({ ...this.draft(), option });
  }

  protected reset(): void {
    this.confirmingReset.set(false);
    this.draft.set({ lines: [...this.original()], option: null });
    clearDraft(this.userId);
    this.results.set([]);
    this.last.set(null);
  }

  protected async run(): Promise<void> {
    const { lines, option } = this.draft();
    if (option === null || this.wait.left() > 0) return;
    this.finish(await this.attempt.submit(RECOVERY_ARDUINO_PATH, { lines, option }));
  }

  protected async retry(): Promise<void> {
    this.finish(await this.attempt.retry());
  }

  private setDraft(draft: ArduinoDraft): void {
    this.draft.set(draft);
    if (draft.option === null && isOriginal(draft.lines, this.original())) clearDraft(this.userId);
    else writeDraft(this.userId, draft);
  }

  private finish(result: RecoveryCheckResult | null): void {
    if (result) {
      this.results.set(result.tests);
      this.last.set({ passed: result.passed, message: result.message, token: result.token });
      this.wait.set(RUN_SECONDS);
      if (result.passed) clearDraft(this.userId);
    }
    this.changed.emit();
  }
}
