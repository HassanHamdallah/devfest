import { ChangeDetectionStrategy, Component, input } from "@angular/core";

let nextUiMetricTileId = 0;

/** Label + big value; exposed as a labelled group so "In room now" etc. are findable by name. */
@Component({
  selector: "ui-metric-tile",
  templateUrl: "./ui-metric-tile.html",
  styleUrl: "./ui-metric-tile.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    role: "group",
    "[attr.aria-labelledby]": "labelId",
  },
})
export class UiMetricTile {
  readonly label = input.required<string>();
  readonly value = input.required<string | number>();
  readonly hint = input<string | null>(null);

  protected readonly labelId = `ui-metric-tile-${nextUiMetricTileId++}`;
}
