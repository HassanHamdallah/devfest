import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  signal,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import {
  effectivePhase,
  remainingSeconds,
} from "../../../core/event/countdown";
import { roomSubmissionPath } from "../../../core/event/event-api";
import {
  Receipt,
  RoomSubmissionRequest,
  TeamBoard,
} from "../../../core/event/event-models";
import { formatTime } from "../../../core/event/format";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiInput } from "../../../shared/ui/ui-input/ui-input";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { UiTextarea } from "../../../shared/ui/ui-textarea/ui-textarea";
import { EventAttemptStatus } from "./attempt-status";
import { PendingAttempt } from "./pending-attempt";
import { submissionAvailability } from "./submission-availability";

const TECHNICAL_OUTPUT_MAX = 4000;
const CLUE_ANSWER_MAX = 500;

/** The answer form for the team's current open room, its receipt, and "Retry" for an unconfirmed answer. */
@Component({
  selector: "event-room-submission-panel",
  imports: [
    EventAttemptStatus,
    FormsModule,
    UiAlert,
    UiButton,
    UiCard,
    UiInput,
    UiStatusChip,
    UiTextarea,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    form,
    .receipt {
      display: grid;
      gap: var(--space-3);
    }
    .receipt {
      margin-top: var(--space-4);
      padding-top: var(--space-4);
      border-top: 1px solid var(--color-border);
    }
    .receipt h3 {
      margin: 0;
      font-size: var(--font-size-base);
    }
    .receipt code {
      overflow-wrap: anywhere;
    }
  `,
  templateUrl: "./room-submission-panel.html",
})
export class EventRoomSubmissionPanel {
  readonly board = input.required<TeamBoard>();
  /** A submission got a response (or was rejected): the page reloads its board and receipts. */
  readonly changed = output<void>();

  protected readonly attempt = new PendingAttempt<
    RoomSubmissionRequest,
    Receipt
  >("submission");
  private readonly now = signal(Date.now());
  private readonly receivedAt = linkedSignal({
    source: this.board,
    computation: () => Date.now(),
  });
  /** The board's phase, followed locally: a running round shows "Time is up" as soon as it reaches 0. */
  private readonly phase = computed(() => {
    const clock = this.board().clock;
    return effectivePhase(
      clock,
      remainingSeconds(clock, this.receivedAt(), this.now()),
    );
  });
  protected readonly availability = computed(() =>
    submissionAvailability(this.board(), this.phase()),
  );
  private readonly openRoomId = computed(() => {
    const availability = this.availability();
    return availability.open ? availability.room.roomId : null;
  });
  private readonly scheduledRoomId = computed(
    () => this.board().scheduledRoom?.roomId ?? null,
  );
  // Drafts and the last receipt belong to one scheduled room; a new round starts with an empty form.
  protected readonly technicalOutput = linkedSignal({
    source: this.scheduledRoomId,
    computation: () => "",
  });
  protected readonly clueAnswer = linkedSignal({
    source: this.scheduledRoomId,
    computation: () => "",
  });
  protected readonly receipt = linkedSignal<number | null, Receipt | null>({
    source: this.scheduledRoomId,
    computation: () => null,
  });
  protected readonly validationError = signal<string | null>(null);
  /** The receipt from the submit response, replaced by the board's copy so a later re-check shows up. */
  protected readonly shownReceipt = computed(() => {
    const local = this.receipt();
    if (!local) return null;
    const fromBoard = this.board().rooms.find(
      (room) => room.lastReceipt?.id === local.id,
    )?.lastReceipt;
    return fromBoard ?? local;
  });
  protected readonly receiptTime = computed(() =>
    formatTime(this.shownReceipt()?.receivedAt ?? null),
  );

  constructor() {
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected async submit(): Promise<void> {
    const roomId = this.openRoomId();
    const body: RoomSubmissionRequest = {
      technicalOutput: this.technicalOutput().trim(),
      clueAnswer: this.clueAnswer().trim(),
    };
    const problem = validate(body);
    this.validationError.set(problem);
    if (roomId === null || problem) return;
    const receipt = await this.attempt.submit(roomSubmissionPath(roomId), body);
    this.finish(receipt);
  }

  protected async retry(): Promise<void> {
    this.finish(await this.attempt.retry());
  }

  private finish(receipt: Receipt | null): void {
    if (receipt) {
      this.receipt.set(receipt);
      if (receipt.clueCorrect) this.clearDrafts();
    }
    this.changed.emit();
  }

  private clearDrafts(): void {
    this.technicalOutput.set("");
    this.clueAnswer.set("");
  }
}

function validate(body: RoomSubmissionRequest): string | null {
  if (!body.technicalOutput) return "Enter your technical output.";
  if (!body.clueAnswer) return "Enter your clue answer.";
  if (body.technicalOutput.length > TECHNICAL_OUTPUT_MAX)
    return `Technical output is too long (max ${TECHNICAL_OUTPUT_MAX} characters).`;
  if (body.clueAnswer.length > CLUE_ANSWER_MAX)
    return `Clue answer is too long (max ${CLUE_ANSWER_MAX} characters).`;
  return null;
}
