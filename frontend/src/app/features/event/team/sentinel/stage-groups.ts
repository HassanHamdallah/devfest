import { ChangeDetectionStrategy, Component, computed, linkedSignal, signal } from "@angular/core";
import { parseCount, parseDecimal } from "./stage-camera";
import { SentinelAxes } from "./sentinel-axes";
import { GroupData, dataOf } from "./sentinel-data";
import { Cluster, assign, makePlot, startClusters, sx, sy, update } from "./sentinel-logic";
import { SentinelStageBase } from "./stage-base";
import { SentinelStageShell } from "./stage-shell";

/** Stage 6: K-Means by hand. Assign gives every frame to its nearest centroid, Update moves the centroids. */
@Component({
  selector: "sentinel-stage-groups",
  imports: [SentinelAxes, SentinelStageShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./sentinel-shared.css",
  styles: `
    /* Dots and centroids move and recolour smoothly; reduced motion switches that off. */
    .dot {
      transition: fill 0.5s ease;
    }
    .centre {
      transition: transform 0.7s ease;
    }
    .centre path {
      fill: var(--color-text);
      stroke: var(--color-surface);
      stroke-width: 2;
    }
    .centre text {
      font-size: 12px;
      font-weight: 700;
      fill: var(--color-text);
      paint-order: stroke;
      stroke: var(--color-surface);
      stroke-width: 3px;
    }
    .dot--0 {
      fill: var(--color-primary);
    }
    .dot--1 {
      fill: var(--brand-red-ink, var(--color-error));
    }
    .dot--none {
      fill: var(--color-text-muted);
    }
    .readout {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: var(--space-1) var(--space-3);
      margin: 0;
    }
    .readout dt {
      font-weight: var(--font-weight-semibold);
    }
    .readout dd {
      margin: 0;
      font-variant-numeric: tabular-nums;
    }
    @media (prefers-reduced-motion: reduce) {
      .dot,
      .centre {
        transition: none;
      }
    }
  `,
  template: `
    <sentinel-stage-shell
      [stage]="stage()"
      [ready]="complete()"
      [readOnly]="readOnly()"
      [submitting]="submitting()"
      [error]="error()"
      [hint]="complete() ? null : 'Enter the size and the centroid of ' + asked().label + '.'"
      (confirmed)="send()"
    >
      <div class="stage stage--split">
        <div>
          <svg class="plot" [attr.viewBox]="'0 0 ' + plot().width + ' ' + plot().height" role="img" aria-labelledby="grp-title grp-desc">
            <title id="grp-title">Camera frames and the two centroids</title>
            <desc id="grp-desc">{{ summary() }}</desc>
            <svg:g sentinelAxes [plot]="plot()" xLabel="brightness" yLabel="shape size" />
            @for (dot of data().dots; track dot.id; let i = $index) {
              <svg:circle [class]="'dot dot--' + (state().assignment[i] ?? 'none')" [attr.cx]="sx(dot.x)" [attr.cy]="sy(dot.y)" r="7" stroke="var(--color-surface)" stroke-width="1.5">
                <svg:title>Frame {{ dot.id }} ({{ dot.x }}, {{ dot.y }})</svg:title>
              </svg:circle>
            }
            @for (centre of state().centres; track $index; let i = $index) {
              <svg:g class="centre" data-centre [style.transform]="'translate(' + sx(centre.x) + 'px,' + sy(centre.y) + 'px)'">
                <svg:path d="M0 -13 L4 -4 L13 0 L4 4 L0 13 L-4 4 L-13 0 L-4 -4 Z" />
                <svg:text x="-14" y="24" text-anchor="end" data-centre-label>{{ data().centroids[i].label }}</svg:text>
              </svg:g>
            }
          </svg>
          <ul class="legend" aria-hidden="true">
            <li><svg width="14" height="14"><circle cx="7" cy="7" r="6" class="pt dot--none" /></svg>not assigned yet</li>
            <li><svg width="14" height="14"><circle cx="7" cy="7" r="6" class="pt dot--0" /></svg>{{ data().centroids[0].label }}</li>
            <li><svg width="14" height="14"><circle cx="7" cy="7" r="6" class="pt dot--1" /></svg>{{ data().centroids[1].label }}</li>
            <li><svg width="16" height="16"><path d="M8 1 L10 6 L15 8 L10 10 L8 15 L6 10 L1 8 L6 6 Z" fill="currentColor" /></svg>centroid</li>
          </ul>
        </div>
        <div class="stage__side">
          <div class="btn-row">
            <button type="button" class="tool tool--primary" data-assign [disabled]="readOnly() || state().settled && state().lastStep === 'update'" (click)="doAssign()">Assign</button>
            <button type="button" class="tool tool--primary" data-update [disabled]="readOnly() || state().lastStep !== 'assign'" (click)="doUpdate()">Update</button>
            <button type="button" class="tool" data-reset [disabled]="state().lastStep === 'none'" (click)="reset()">Start again</button>
          </div>
          <p class="note" aria-live="polite" data-hint>
            @if (state().lastStep === 'none') {
              Press Assign: every frame joins its nearest centroid.
            } @else if (state().lastStep === 'assign') {
              Now press Update: each centroid moves to the middle of its frames.
            } @else if (state().settled) {
              Nothing moved. The groups are final.
            } @else {
              Centroids moved. Press Assign again.
            }
          </p>
          <dl class="readout" data-readout>
            @for (centre of state().centres; track $index; let i = $index) {
              <dt>{{ data().centroids[i].label }}</dt>
              <dd>{{ sizes()[i] }} frames · centre ({{ centre.x.toFixed(2) }}, {{ centre.y.toFixed(2) }})</dd>
            }
          </dl>
          <fieldset>
            <legend>{{ asked().label }} (the van's frames)</legend>
            <div class="fields">
              <div class="field"><label for="grp-size">Frames</label><input id="grp-size" inputmode="numeric" autocomplete="off" [value]="size()" (input)="size.set(text($event))" [disabled]="readOnly()" /></div>
              <div class="field"><label for="grp-x">Centroid x</label><input id="grp-x" inputmode="decimal" autocomplete="off" [value]="x()" (input)="x.set(text($event))" [disabled]="readOnly()" /></div>
              <div class="field"><label for="grp-y">Centroid y</label><input id="grp-y" inputmode="decimal" autocomplete="off" [value]="y()" (input)="y.set(text($event))" [disabled]="readOnly()" /></div>
            </div>
          </fieldset>
        </div>
      </div>
    </sentinel-stage-shell>
  `,
})
export class SentinelStageGroups extends SentinelStageBase {
  protected readonly data = computed(() => dataOf<GroupData>(this.stage()));
  protected readonly plot = computed(() => {
    const d = this.data();
    return makePlot([...d.dots.map((p) => p.x), ...d.centroids.map((c) => c.x)], [...d.dots.map((p) => p.y), ...d.centroids.map((c) => c.y)]);
  });
  /** Starts again only when the stage's frames change, not on every refetch of the activity. */
  protected readonly state = linkedSignal<string, Cluster>({
    source: () => this.data().dots.map((d) => d.id).join(","),
    computation: () => startClusters(this.data().dots, this.data().centroids),
  });
  protected readonly asked = computed(() => this.data().centroids.find((c) => c.id === this.data().asked) ?? this.data().centroids[0]);
  protected readonly sizes = computed(() => this.data().centroids.map((_, i) => this.state().assignment.filter((a) => a === i).length));
  protected readonly summary = computed(() =>
    this.state().lastStep === "none" ? "Frames not assigned yet." : this.data().centroids.map((c, i) => `${c.label}: ${this.sizes()[i]} frames`).join(", "),
  );
  protected readonly size = signal("");
  protected readonly x = signal("");
  protected readonly y = signal("");
  protected readonly complete = computed(() => parseCount(this.size()) !== null && parseDecimal(this.x()) !== null && parseDecimal(this.y()) !== null);

  protected sx(value: number): number {
    return sx(this.plot(), value);
  }

  protected sy(value: number): number {
    return sy(this.plot(), value);
  }

  protected text(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected reset(): void {
    this.state.set(startClusters(this.data().dots, this.data().centroids));
  }

  protected doAssign(): void {
    this.state.update((s) => assign(s, this.data().dots));
  }

  protected doUpdate(): void {
    this.state.update((s) => update(s, this.data().dots));
  }

  protected send(): void {
    void this.submit({ size: parseCount(this.size()), x: parseDecimal(this.x()), y: parseDecimal(this.y()) });
  }
}
