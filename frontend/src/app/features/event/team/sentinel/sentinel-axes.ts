import { ChangeDetectionStrategy, Component, computed, input } from "@angular/core";
import { Plot, sx, sy, ticks } from "./sentinel-logic";

/** Grid, ticks and axis titles of a scatter plot, drawn inside the stage's own <svg> (no charting library). */
@Component({
  selector: "svg:g[sentinelAxes]",
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @for (tick of xTicks(); track tick.value) {
      <svg:line class="grid" [attr.x1]="tick.at" [attr.x2]="tick.at" [attr.y1]="plot().top" [attr.y2]="plot().height - plot().bottom" />
      <svg:text class="tick" [attr.x]="tick.at" [attr.y]="plot().height - plot().bottom + 16" text-anchor="middle">{{ tick.value }}</svg:text>
    }
    @for (tick of yTicks(); track tick.value) {
      <svg:line class="grid" [attr.x1]="plot().left" [attr.x2]="plot().width - plot().right" [attr.y1]="tick.at" [attr.y2]="tick.at" />
      <svg:text class="tick" [attr.x]="plot().left - 8" [attr.y]="tick.at + 4" text-anchor="end">{{ tick.value }}</svg:text>
    }
    <svg:line class="axis" [attr.x1]="plot().left" [attr.x2]="plot().width - plot().right" [attr.y1]="plot().height - plot().bottom" [attr.y2]="plot().height - plot().bottom" />
    <svg:line class="axis" [attr.x1]="plot().left" [attr.x2]="plot().left" [attr.y1]="plot().top" [attr.y2]="plot().height - plot().bottom" />
    <svg:text class="axis-title" [attr.x]="(plot().left + plot().width - plot().right) / 2" [attr.y]="plot().height - 6" text-anchor="middle">{{ xLabel() }}</svg:text>
    <svg:text class="axis-title" [attr.transform]="'translate(14 ' + (plot().top + plot().height - plot().bottom) / 2 + ') rotate(-90)'" text-anchor="middle">{{ yLabel() }}</svg:text>
  `,
  styles: `
    .grid {
      stroke: var(--color-border);
      stroke-width: 1;
    }
    .axis {
      stroke: var(--color-text-muted);
      stroke-width: 1.5;
    }
    .tick {
      font-size: 12px;
      fill: var(--color-text-muted);
    }
    .axis-title {
      font-size: 13px;
      font-weight: 600;
      fill: var(--color-text);
    }
    @media (max-width: 600px) {
      .tick {
        font-size: 17px;
      }
      .axis-title {
        font-size: 18px;
      }
    }
  `,
})
export class SentinelAxes {
  readonly plot = input.required<Plot>();
  readonly xLabel = input("");
  readonly yLabel = input("");

  protected readonly xTicks = computed(() => ticks(this.plot().x0, this.plot().x1).map((value) => ({ value, at: sx(this.plot(), value) })));
  protected readonly yTicks = computed(() => ticks(this.plot().y0, this.plot().y1).map((value) => ({ value, at: sy(this.plot(), value) })));
}
