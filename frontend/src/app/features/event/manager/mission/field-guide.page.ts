import { ChangeDetectionStrategy, Component, inject } from "@angular/core";
import { ActivatedRoute, RouterLink } from "@angular/router";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";

/**
 * /event/room/:roomId/mission/guide: the SENTINEL Field Guide, one A4 page for the organizers to print and hand out.
 * It explains the ideas behind the ten stages and holds no data, answer or score of the room. The global print
 * styles hide the application chrome, so only this page reaches the printer.
 */
@Component({
  imports: [RouterLink, UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./field-guide.page.css",
  templateUrl: "./field-guide.page.html",
})
export class EventFieldGuidePage {
  protected readonly roomId = Number(inject(ActivatedRoute).snapshot.paramMap.get("roomId"));

  protected print(): void {
    window.print();
  }
}
