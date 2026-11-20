import { ChangeDetectionStrategy, Component, computed, signal } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { UiSelect } from "../../../../../shared/ui/ui-select/ui-select";
import { MlStageBase } from "./ml-stage-base";
import { EventRecoveryStageForm } from "./recovery-stage-form";

/** Stage 3: the sensor table, and one cleaning choice for missing values and one for outliers. */
@Component({
  selector: "event-ml-cleaning",
  imports: [EventRecoveryStageForm, FormsModule, UiSelect],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./ml-stage.css",
  styles: `
    table {
      inline-size: 100%;
      border-collapse: collapse;
      font-variant-numeric: tabular-nums;
    }
    th,
    td {
      padding: var(--space-2) var(--space-3);
      text-align: start;
      border-block-end: 1px solid var(--color-border);
    }
    th {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
  `,
  template: `<event-recovery-stage-form
    [action]="stage().action"
    [busy]="busy()"
    [waitLeft]="waitLeft()"
    (submitted)="send()"
  >
    @if (cleaning(); as c) {
      <div class="event-table-wrap">
        <table data-ml-table>
          <caption class="event-sr-only">
            Sensor records
          </caption>
          <thead>
            <tr>
              @for (column of c.table.columns; track column) {
                <th scope="col">{{ column }}</th>
              }
            </tr>
          </thead>
          <tbody>
            @for (row of c.table.rows; track $index) {
              <tr>
                @for (cell of row; track $index) {
                  <td>{{ cell }}</td>
                }
              </tr>
            }
          </tbody>
        </table>
      </div>
      <div class="grid2">
        <ui-select
          [label]="c.missing.label"
          name="missing"
          [options]="missingOptions()"
          [ngModel]="missing()"
          (ngModelChange)="missingPick.set($event)"
        />
        <ui-select
          [label]="c.outliers.label"
          name="outliers"
          [options]="outlierOptions()"
          [ngModel]="outliers()"
          (ngModelChange)="outlierPick.set($event)"
        />
      </div>
    }
  </event-recovery-stage-form>`,
})
export class EventMlCleaning extends MlStageBase {
  protected readonly cleaning = computed(() => this.stage().cleaning);
  protected readonly missingOptions = computed(() =>
    (this.cleaning()?.missing.options ?? []).map((o) => ({ value: o.id, label: o.label })),
  );
  protected readonly outlierOptions = computed(() =>
    (this.cleaning()?.outliers.options ?? []).map((o) => ({ value: o.id, label: o.label })),
  );
  protected readonly missingPick = signal<string | null>(null);
  protected readonly outlierPick = signal<string | null>(null);
  /** The first option until the team picks another (the prototype starts on "Replace with 0" and "Ignore"). */
  protected readonly missing = computed(() => this.missingPick() ?? this.missingOptions()[0]?.value ?? "");
  protected readonly outliers = computed(() => this.outlierPick() ?? this.outlierOptions()[0]?.value ?? "");

  protected send(): void {
    this.answer.emit({ missing: this.missing(), outliers: this.outliers() });
  }
}
