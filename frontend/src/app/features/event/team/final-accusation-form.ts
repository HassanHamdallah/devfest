import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  signal,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { ACCUSATION_PATH } from "../../../core/event/event-api";
import {
  AccusationRequest,
  AccusationResult,
  TeamFinal,
} from "../../../core/event/event-models";
import { formatPenaltyMinutes } from "../../../core/event/format";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiCopyButton } from "../../../shared/ui/ui-copy-button/ui-copy-button";
import {
  UiSelect,
  UiSelectOption,
} from "../../../shared/ui/ui-select/ui-select";
import { UiTextarea } from "../../../shared/ui/ui-textarea/ui-textarea";
import { EventAttemptStatus } from "./attempt-status";
import { PendingAttempt } from "./pending-attempt";

const TEXT_MAX = 4000;

/** MESSAGE stage: decrypt the message and accuse one suspect. A wrong accusation adds +3 minutes. */
@Component({
  selector: "event-final-accusation-form",
  imports: [
    EventAttemptStatus,
    FormsModule,
    UiAlert,
    UiButton,
    UiCard,
    UiCopyButton,
    UiSelect,
    UiTextarea,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./final-accusation-form.css",
  templateUrl: "./final-accusation-form.html",
})
export class EventFinalAccusationForm {
  readonly final = input.required<TeamFinal>();
  /** An accusation got a response: the page reloads the final. */
  readonly changed = output<void>();

  protected readonly attempt = new PendingAttempt<
    AccusationRequest,
    AccusationResult
  >("accusation");
  protected readonly suspectId = signal("");
  protected readonly decryptedMessage = signal("");
  protected readonly evidence = signal("");
  protected readonly feedback = signal<string | null>(null);

  protected readonly suspectOptions = computed<UiSelectOption[]>(() =>
    this.final().suspects.map((s) => ({ value: String(s.id), label: s.name })),
  );
  protected readonly attemptsLine = computed(() => {
    const { accusationAttempts, wrongAccusations, penaltyMinutes } =
      this.final();
    return `Accusations so far: ${accusationAttempts} (${wrongAccusations} wrong). Active penalties: ${formatPenaltyMinutes(penaltyMinutes)}.`;
  });

  protected async submit(): Promise<void> {
    const body = this.buildRequest();
    if (!body) return;
    this.finish(await this.attempt.submit(ACCUSATION_PATH, body));
  }

  protected async retry(): Promise<void> {
    this.finish(await this.attempt.retry());
  }

  private buildRequest(): AccusationRequest | null {
    const body: AccusationRequest = {
      suspectId: Number(this.suspectId()),
      decryptedMessage: this.decryptedMessage().trim(),
      evidence: this.evidence().trim(),
    };
    const problem = validate(body);
    this.feedback.set(problem);
    return problem ? null : body;
  }

  private finish(result: AccusationResult | null): void {
    if (result && !result.correct) {
      this.feedback.set("Incorrect accusation — +3 minutes. Try again.");
    }
    this.changed.emit();
  }
}

function validate(body: AccusationRequest): string | null {
  if (!body.suspectId) return "Choose a suspect.";
  if (!body.decryptedMessage) return "Enter the decrypted message.";
  if (!body.evidence) return "Enter your evidence.";
  if (
    body.decryptedMessage.length > TEXT_MAX ||
    body.evidence.length > TEXT_MAX
  )
    return `Each text can be at most ${TEXT_MAX} characters.`;
  return null;
}
