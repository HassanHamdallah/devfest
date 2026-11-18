import { ChangeDetectionStrategy, Component, inject } from "@angular/core";
import { ActivatedRoute, RouterLink } from "@angular/router";
import { UiButton } from "../../../../shared/ui/ui-button/ui-button";
import { SentinelBrief } from "../../team/sentinel/sentinel-brief";

/**
 * /event/room/:roomId/mission/guide: the SENTINEL Field Guide, one A4 page for the organizers to print and hand out.
 * It explains the ideas behind the ten stages and holds no data, answer or score of the room. The global print
 * styles hide the application chrome, so only this page reaches the printer.
 */
@Component({
  imports: [RouterLink, SentinelBrief, UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./field-guide.page.css",
  templateUrl: "./field-guide.page.html",
})
export class EventFieldGuidePage {
  /** The rules as "$$ " lines: the same display formulas the stages show. They hold no number of any stage. */
  protected readonly formulas = {
    zScore: "$$ z = (x - mean) \u00f7 sd",
    iqr: ["$$ IQR = Q3 - Q1", "$$ Lower fence = Q1 - k x IQR", "$$ Upper fence = Q3 + k x IQR"].join("\n"),
    neuron: ["$$ z = w1\u00b7x1 + w2\u00b7x2 + b", "$$ ReLU(z) = max(0, z)"].join("\n"),
    metrics: ["$$ Precision = TP / (TP + FP)", "$$ Recall = TP / (TP + FN)"].join("\n"),
    aStar: "$$ f = g + h",
    entropy: ["$$ Entropy = -\u03a3 p\u00b7log2(p)", "$$ Gain = Entropy(parent) \u2212 weighted average entropy(children)"].join("\n"),
  };

  protected readonly roomId = Number(inject(ActivatedRoute).snapshot.paramMap.get("roomId"));

  protected print(): void {
    window.print();
  }
}
