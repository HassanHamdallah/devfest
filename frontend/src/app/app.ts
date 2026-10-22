import { Component, inject } from "@angular/core";
import { RouterOutlet, RouterLink, RouterLinkActive } from "@angular/router";
import { Api } from "./core/api";
import { UiButton } from "./shared/ui/ui-button/ui-button";
@Component({
  selector: "app-root",
  imports: [RouterOutlet, RouterLink, RouterLinkActive, UiButton],
  template: `@if (api.user(); as user) {
      <div class="app-shell">
        <aside class="sidebar">
          <a routerLink="/contest" class="brand"
            ><span class="brand-icon">&lt;&gt;</span
            ><span>DevFest<small>CODE ARENA</small></span></a
          >
          @if (user.role !== "PARTICIPANT") {
            <p class="nav-label">INVESTIGATION</p>
            <nav aria-label="Investigation navigation">
              <a routerLink="/investigation" routerLinkActive="active"
                >Rooms and teams</a
              >
            </nav>
          }
          @if (user.role === "ADMIN" || user.role === "PARTICIPANT") {
            <p class="nav-label">CODING CONTEST</p>
            <nav aria-label="Participant navigation">
              @for (link of links; track link.path) {
                <a [routerLink]="link.path" routerLinkActive="active">{{
                  link.label
                }}</a>
              }
            </nav>
          }
          @if (user.role === "ADMIN") {
            <p class="nav-label">ORGANIZER</p>
            <nav aria-label="Admin navigation">
              @for (link of adminLinks; track link.path) {
                <a [routerLink]="link.path" routerLinkActive="active">{{
                  link.label
                }}</a>
              }
            </nav>
          }
          <div class="sidebar-bottom">
            <strong>{{ user.name }}</strong
            ><small>{{
              user.role === "ADMIN"
                ? "Organizer"
                : user.role === "MANAGER"
                  ? "Room manager"
                  : user.role === "MEMBER"
                    ? "Team representative"
                    : "Participant"
            }}</small
            ><ui-button variant="ghost" (click)="api.logout()"
              >Sign out</ui-button
            >
          </div>
        </aside>
        <main>
          <header class="topbar">
            <span>DevFest competition platform</span>
          </header>
          <div class="page"><router-outlet /></div>
        </main>
      </div>
    } @else {
      <router-outlet />
    }`,
})
export class App {
  api = inject(Api);
  links = [
    { path: "/contest", label: "◈  Contest" },
    { path: "/problems", label: "⌘  Problems" },
    { path: "/submissions", label: "↗  My Submissions" },
    { path: "/scoreboard", label: "▤  Scoreboard" },
  ];
  adminLinks = [
    { path: "/admin/overview", label: "Overview" },
    { path: "/admin/participants", label: "Participants" },
    { path: "/admin/problems", label: "Problems" },
    { path: "/admin/submissions", label: "Submissions" },
    { path: "/admin/contest", label: "Contest Settings" },
  ];
}
