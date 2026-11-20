import { ChangeDetectionStrategy, Component, input, output } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { UiButton } from "../../../../../shared/ui/ui-button/ui-button";

/** The form around one stage's controls: the stage's own action text as the button, disabled during the rate limit. */
@Component({
  selector: "event-recovery-stage-form",
  imports: [FormsModule, UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    form {
      display: grid;
      gap: var(--space-4);
    }
  `,
  template: `<form (ngSubmit)="submitted.emit()">
    <ng-content />
    <ui-button type="submit" size="lg" [fullWidth]="true" [loading]="busy()" [disabled]="disabled() || waitLeft() > 0" data-recovery-action>{{
      waitLeft() > 0 ? "Wait " + waitLeft() + " s" : action()
    }}</ui-button>
  </form>`,
})
export class EventRecoveryStageForm {
  readonly action = input.required<string>();
  readonly busy = input(false);
  readonly waitLeft = input(0);
  /** True while the stage has nothing to send yet (no model picked). */
  readonly disabled = input(false);
  readonly submitted = output<void>();
}
