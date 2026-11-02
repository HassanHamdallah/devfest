import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
} from "@angular/core";
import { RouterOutlet } from "@angular/router";
import { Api } from "./core/api";
import { RevisionPoller } from "./core/event/revision-poller";
import { isEventRole } from "./core/role-home";
import { CodingShell } from "./core/shell/coding-shell";
import { EventShell } from "./core/shell/event-shell";

// Default change detection on purpose: the legacy coding pages render plain fields and relied on
// being refreshed from the root on every tick. The event shell below is OnPush.
@Component({
  selector: "app-root",
  imports: [RouterOutlet, CodingShell, EventShell],
  changeDetection: ChangeDetectionStrategy.Default,
  template: `@if (api.user(); as user) {
      @if (user.role === "PARTICIPANT") {
        <app-coding-shell [user]="user" />
      } @else {
        <app-event-shell [user]="user" />
      }
    } @else {
      <router-outlet />
    }`,
})
export class App {
  protected readonly api = inject(Api);
  private readonly poller = inject(RevisionPoller);

  constructor() {
    // Login and session restore both set the user; logout clears it.
    effect(() => {
      const user = this.api.user();
      if (user && isEventRole(user.role)) this.poller.start();
      else this.poller.stop();
    });
  }
}
