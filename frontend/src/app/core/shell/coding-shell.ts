import {
  ChangeDetectionStrategy,
  Component,
  inject,
  input,
} from "@angular/core";
import { RouterLink, RouterLinkActive, RouterOutlet } from "@angular/router";
import { Api } from "../api";
import { User } from "../models";
import { UiButton } from "../../shared/ui/ui-button/ui-button";

/**
 * The original Code Arena shell, shown to PARTICIPANT accounts only. Default change detection like the
 * coding pages it hosts, which update plain fields and expect to be refreshed on every tick.
 */
@Component({
  selector: "app-coding-shell",
  imports: [RouterOutlet, RouterLink, RouterLinkActive, UiButton],
  changeDetection: ChangeDetectionStrategy.Default,
  host: { style: "display: block" },
  template: `<div class="app-shell">
    <aside class="sidebar">
      <a routerLink="/contest" class="brand"
        ><span class="brand-icon">&lt;&gt;</span
        ><span>DevFest<small>CODE ARENA</small></span></a
      >
      <p class="nav-label">CODING CONTEST</p>
      <nav aria-label="Participant navigation">
        @for (link of links; track link.path) {
          <a [routerLink]="link.path" routerLinkActive="active">{{
            link.label
          }}</a>
        }
      </nav>
      <div class="sidebar-bottom">
        <strong>{{ user().name }}</strong
        ><small>Participant</small
        ><ui-button variant="ghost" (click)="api.logout()">Sign out</ui-button>
      </div>
    </aside>
    <main>
      <header class="topbar">
        <span>DevFest competition platform</span>
      </header>
      <div class="page"><router-outlet /></div>
    </main>
  </div>`,
})
export class CodingShell {
  protected readonly api = inject(Api);
  readonly user = input.required<User>();
  protected readonly links = [
    { path: "/contest", label: "◈  Contest" },
    { path: "/problems", label: "⌘  Problems" },
    { path: "/submissions", label: "↗  My Submissions" },
    { path: "/scoreboard", label: "▤  Scoreboard" },
  ];
}
