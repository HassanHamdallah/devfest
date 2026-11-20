import { ChangeDetectionStrategy, Component, computed, signal } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { UiInput } from "../../../../../shared/ui/ui-input/ui-input";
import { MlStageBase } from "./ml-stage-base";
import { EventRecoveryStageForm } from "./recovery-stage-form";

/** Stage 6: four records (as sent, not sorted) and the word the team reads from them. */
@Component({
  selector: "event-ml-evidence",
  imports: [EventRecoveryStageForm, FormsModule, UiInput],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./ml-stage.css",
  styles: `
    .records {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      gap: var(--space-3);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .record {
      display: grid;
      gap: var(--space-1);
      padding: var(--space-3) var(--space-4);
      background: var(--color-surface-subtle);
      border: var(--border-outline-thin);
      border-radius: var(--radius-md);
    }
    .record b {
      font-size: var(--font-size-lg);
    }
    .record span {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
      font-variant-numeric: tabular-nums;
      overflow-wrap: anywhere;
    }
  `,
  template: `<event-recovery-stage-form
    [action]="stage().action"
    [busy]="busy()"
    [waitLeft]="waitLeft()"
    [disabled]="word().trim() === ''"
    (submitted)="send()"
  >
    @if (evidence(); as e) {
      <ul class="records" aria-label="Suspicious records" data-ml-records>
        @for (record of e.records; track record.id) {
          <li class="record">
            <b>{{ record.id }}</b>
            <span>{{ record.time }} • {{ record.device }} • {{ record.confidence }}</span>
          </li>
        }
      </ul>
      <ui-input
        [label]="e.inputLabel"
        name="word"
        [placeholder]="e.placeholder ?? ''"
        [ngModel]="word()"
        (ngModelChange)="word.set($event)"
      />
    }
  </event-recovery-stage-form>`,
})
export class EventMlEvidence extends MlStageBase {
  protected readonly evidence = computed(() => this.stage().evidence);
  protected readonly word = signal("");

  protected send(): void {
    const word = this.word().trim();
    if (word !== "") this.answer.emit({ word });
  }
}
