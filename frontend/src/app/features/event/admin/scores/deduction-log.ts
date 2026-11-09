import { ChangeDetectionStrategy, Component, computed, input, output, signal } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { DeductionView } from "../../../../core/event/event-models";
import { UiSelect, UiSelectOption } from "../../../../shared/ui/ui-select/ui-select";
import { EventDeductionItem } from "../../manager/deduction-item";

/**
 * The full penalty log: every hint and cheating record, newest first, active and cancelled, with who recorded it,
 * when, and who cancelled it, when and why. Append-only; the admin may cancel a row here.
 */
@Component({
  selector: "app-deduction-log",
  imports: [EventDeductionItem, FormsModule, UiSelect],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      gap: var(--space-3);
    }
    .list {
      display: grid;
    }
  `,
  template: `<ui-select
      label="Show"
      [compact]="true"
      [options]="options"
      [ngModel]="filter()"
      (ngModelChange)="filter.set($event)"
    />
    <p class="event-muted" data-log-count>{{ shown().length }} of {{ log().length }} entries</p>
    <div class="list" data-deduction-log>
      @for (deduction of shown(); track deduction.id) {
        <event-deduction-item [deduction]="deduction" [showTeam]="true" (changed)="changed.emit()" />
      } @empty {
        <span class="event-muted">No hints or cheating records yet.</span>
      }
    </div>`,
})
export class DeductionLog {
  readonly log = input.required<readonly DeductionView[]>();
  readonly changed = output<void>();

  protected readonly options: UiSelectOption[] = [
    { value: "ALL", label: "All entries" },
    { value: "HINT", label: "Hints" },
    { value: "CHEATING", label: "Cheating" },
    { value: "ACTIVE", label: "Active only" },
    { value: "CANCELLED", label: "Cancelled only" },
  ];
  protected readonly filter = signal("ALL");
  protected readonly shown = computed(() =>
    this.log().filter((d) => {
      switch (this.filter()) {
        case "HINT":
        case "CHEATING":
          return d.kind === this.filter();
        case "ACTIVE":
          return !d.cancelled;
        case "CANCELLED":
          return d.cancelled;
        default:
          return true;
      }
    }),
  );
}
