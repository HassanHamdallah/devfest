import { ChangeDetectionStrategy, Component, input } from "@angular/core";
import { RouterLink } from "@angular/router";

/**
 * The header of the room manager's sub-pages (SENTINEL, Linux dashboard, Problem Solving results): the same back
 * link to the room board, the page title, and the page's own chips and actions projected on the line below. The
 * room colour comes from the page's `room-tone--*` class.
 */
@Component({
  selector: "event-room-subpage-header",
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    header {
      display: grid;
      gap: var(--space-2);
      padding-inline-start: var(--space-4);
      border-inline-start: 6px solid var(--room-color);
    }
    h1 {
      margin: 0;
    }
    a {
      color: var(--color-primary-text);
      font-weight: var(--font-weight-semibold);
    }
  `,
  template: `<header data-room-subpage-header>
    <a [routerLink]="['/event/room', roomId()]">← {{ roomName() }} board</a>
    <h1>{{ title() }}</h1>
    <div class="event-inline"><ng-content /></div>
  </header>`,
})
export class EventRoomSubpageHeader {
  readonly roomId = input.required<number>();
  readonly roomName = input.required<string>();
  readonly title = input.required<string>();
}
