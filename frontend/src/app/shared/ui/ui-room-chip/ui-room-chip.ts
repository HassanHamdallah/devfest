import { ChangeDetectionStrategy, Component, input } from "@angular/core";
import { RoomCode } from "../../../core/event/event-models";

export type UiRoomChipSize = "sm" | "md";

/**
 * A room as a coloured capsule: a solid dot in the room's colour and the room name in dark text, so the
 * colour is never the only signal. AI blue, Problem solving red, Linux green, Hardware yellow.
 * Deliberately has no data-room attribute; pages use that to find their own room cards.
 */
@Component({
  selector: "ui-room-chip",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    "[class]": "'ui-room-chip ui-room-chip--' + size() + ' room-tone--' + code()",
  },
  template: `<span class="ui-room-chip__dot" aria-hidden="true"></span
    >{{ name() }}`,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
      padding: 2px var(--space-3) 2px var(--space-2);
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-semibold);
      line-height: var(--line-height-base);
      color: var(--color-text);
      white-space: nowrap;
      background: var(--room-tint);
      border: 1.5px solid var(--color-outline);
      border-radius: var(--radius-full);
    }
    :host(.ui-room-chip--sm) {
      font-size: var(--font-size-xs);
      border-width: 1px;
    }
    .ui-room-chip__dot {
      inline-size: 0.7em;
      block-size: 0.7em;
      background: var(--room-color);
      border: 1px solid var(--color-outline);
      border-radius: 50%;
    }
  `,
})
export class UiRoomChip {
  readonly code = input.required<RoomCode>();
  readonly name = input.required<string>();
  readonly size = input<UiRoomChipSize>("md");
}
