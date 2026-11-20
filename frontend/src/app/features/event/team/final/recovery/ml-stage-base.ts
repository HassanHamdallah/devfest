import { Directive, input, output } from "@angular/core";
import { MlCheckRequest, MlStageView } from "../../../../../core/event/event-models";

/**
 * What every ML stage screen shares: the stage the server sent, whether a check is on its way, the seconds left of the
 * rate limit, and the answer it emits when the team presses the stage's action. The screen only builds the answer; the
 * server decides whether it passes.
 */
@Directive()
export abstract class MlStageBase {
  readonly stage = input.required<MlStageView>();
  readonly busy = input(false);
  readonly waitLeft = input(0);
  readonly answer = output<MlCheckRequest>();
}
