import { ChangeDetectionStrategy, Component, computed, signal } from "@angular/core";
import { SentinelAxes } from "./sentinel-axes";
import { OutlierData, dataOf, plain, runOf } from "./sentinel-data";
import { makePlot, sx, sy } from "./sentinel-logic";
import { SentinelStageBase } from "./stage-base";
import { SentinelStageShell } from "./stage-shell";

/** Stage 1: pick a detector and a threshold, run it as often as you like, submit the setting you trust. */
@Component({
  selector: "sentinel-stage-outlier",
  imports: [SentinelAxes, SentinelStageShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./sentinel-shared.css",
  template: `
    <sentinel-stage-shell
      [stage]="stage()"
      [ready]="selected() !== null"
      [readOnly]="readOnly()"
      [submitting]="submitting()"
      [error]="error()"
      submitLabel="Submit this setting"
      [hint]="selected() === null ? 'Choose a detector and a threshold.' : 'Submits the setting you chose.'"
      (confirmed)="submitSelected()"
    >
      <div class="stage stage--split">
        <div>
          <svg class="plot" [attr.viewBox]="'0 0 ' + plot().width + ' ' + plot().height" role="img" aria-labelledby="out-title out-desc">
            <title id="out-title">GPS pings of the white van</title>
            <desc id="out-desc">
              {{ data().readings.length }} pings. {{ flagged() === null ? 'No run shown yet.' : flagged()!.length + ' flagged by the run shown.' }}
            </desc>
            <svg:g sentinelAxes [plot]="plot()" xLabel="km east" yLabel="km north" />
            @for (point of data().readings; track point.id) {
              <svg:circle
                class="pt"
                [class.pt--flag]="isFlagged(point.id)"
                [attr.cx]="sx(point.x)"
                [attr.cy]="sy(point.y)"
                [attr.r]="isFlagged(point.id) ? 8 : 6"
              >
                <svg:title>{{ point.label }} ({{ point.x }}, {{ point.y }}){{ isFlagged(point.id) ? ' flagged' : '' }}</svg:title>
              </svg:circle>
              @if (isFlagged(point.id)) {
                <svg:text class="pt-label" [attr.x]="sx(point.x) + 10" [attr.y]="sy(point.y) - 8">{{ point.label }}</svg:text>
              }
            }
          </svg>
          <ul class="legend" aria-hidden="true">
            <li><svg width="14" height="14"><circle cx="7" cy="7" r="6" class="pt" /></svg>kept</li>
            <li><svg width="14" height="14"><circle cx="7" cy="7" r="6" class="pt pt--flag" /></svg>flagged by the run shown</li>
          </ul>
        </div>
        <div class="stage__side">
          <fieldset>
            <legend>Detector</legend>
            <div class="opts">
              <label class="opt"><input type="radio" name="out-method" [checked]="method() === 'IQR'" [disabled]="readOnly()" (change)="pickMethod('IQR')" />IQR (box plot fences)</label>
              <label class="opt"><input type="radio" name="out-method" [checked]="method() === 'Z'" [disabled]="readOnly()" (change)="pickMethod('Z')" />Z-score</label>
            </div>
          </fieldset>
          <fieldset>
            <legend>Threshold</legend>
            <div class="opts">
              @for (value of thresholds(); track value) {
                <label class="opt"><input type="radio" name="out-threshold" [checked]="threshold() === value" [disabled]="readOnly()" (change)="threshold.set(value)" />{{ method() === 'IQR' ? plain(value) + ' × IQR' : plain(value) + ' standard deviations' }}</label>
              }
            </div>
          </fieldset>
          <div class="btn-row">
            <button type="button" class="tool tool--primary" data-run [disabled]="selected() === null || readOnly() || running() !== null" (click)="runSelected()">Run detector</button>
            <span class="event-muted">Runs are unlimited.</span>
          </div>
          <div class="panel" aria-live="polite" data-run-result>
            @if (selected() === null) {
              <p class="event-muted">Pick a setting to see what it flags.</p>
            } @else if (flagged() === null) {
              <p>This setting has not been run yet.</p>
            } @else if (flagged()!.length === 0) {
              <p><strong>Flags nothing.</strong> Every ping is kept.</p>
            } @else {
              <p><strong>Flags {{ flagged()!.length }}:</strong> {{ flaggedNames() }}</p>
            }
            @if (stage().runs.length > 0) {
              <p class="event-muted">Runs so far: {{ stage().runs.length }}</p>
            }
          </div>
        </div>
      </div>
    </sentinel-stage-shell>
  `,
})
export class SentinelStageOutlier extends SentinelStageBase {
  protected readonly plain = plain;
  protected readonly data = computed(() => dataOf<OutlierData>(this.stage()));
  protected readonly plot = computed(() =>
    makePlot(
      this.data().readings.map((r) => r.x),
      this.data().readings.map((r) => r.y),
    ),
  );
  protected readonly method = signal<"IQR" | "Z">("IQR");
  protected readonly threshold = signal<number | null>(null);
  protected readonly thresholds = computed(() =>
    this.data()
      .configs.filter((c) => c.method === this.method())
      .map((c) => c.threshold)
      .sort((a, b) => a - b),
  );
  protected readonly selected = computed(
    () => this.data().configs.find((c) => c.method === this.method() && c.threshold === this.threshold()) ?? null,
  );
  /** The flagged ids of the selected setting's run, or null when it was not run. */
  protected readonly flagged = computed<string[] | null>(() => {
    const config = this.selected();
    const run = config ? runOf(this.stage(), config.id) : null;
    return run ? (run.result["flagged"] as string[]) : null;
  });
  protected readonly flaggedNames = computed(() =>
    this.data()
      .readings.filter((r) => this.flagged()?.includes(r.id))
      .map((r) => r.label)
      .join(", "),
  );

  protected sx(value: number): number {
    return sx(this.plot(), value);
  }

  protected sy(value: number): number {
    return sy(this.plot(), value);
  }

  protected isFlagged(id: string): boolean {
    return this.flagged()?.includes(id) ?? false;
  }

  protected pickMethod(method: "IQR" | "Z"): void {
    this.method.set(method);
    this.threshold.set(null);
  }

  protected runSelected(): void {
    const config = this.selected();
    if (config) void this.run(config.id);
  }

  protected submitSelected(): void {
    const config = this.selected();
    if (config) void this.submit({ config: config.id });
  }
}
