import { Component, inject, signal, OnInit } from "@angular/core";
import { DatePipe } from "@angular/common";
import { RouterLink } from "@angular/router";
import { Api, message } from "../core/api";
import { Contest as ContestData } from "../core/models";
import { UiCard } from "../shared/ui/ui-card/ui-card";
@Component({
  imports: [DatePipe, RouterLink, UiCard],
  template: `<header class="page-heading">
      <span class="eyebrow">YOUR CONTEST DESK</span>
      <h1>{{ contest()?.title || "Contest" }}</h1>
      <p>Think clearly. Build carefully. Keep going.</p>
    </header>
    @if (error()) {
      <p role="alert">{{ error() }}</p>
    } @else if (!contest()) {
      <p role="status">Loading contest…</p>
    } @else {
      <div class="hero">
        <div>
          <span class="eyebrow">ONE CONTEST · THREE LANGUAGES</span>
          <h2>Ready for the next challenge?</h2>
          <p>
            Your work is saved as you go. Run a sample, test an idea, then
            submit your solution.
          </p>
          <a class="action-link" routerLink="/problems">Explore problems →</a>
        </div>
        <div class="code-art" aria-hidden="true">&lt; / &gt;</div>
      </div>
      <div class="cards">
        <ui-card
          ><h3>Starts</h3>
          <p>{{ contest()!.startAt | date: "medium" }}</p></ui-card
        ><ui-card
          ><h3>Ends</h3>
          <p>{{ contest()!.endAt | date: "medium" }}</p></ui-card
        ><ui-card
          ><h3>Scoring</h3>
          <p>
            One point per solved problem. Earlier final contributing acceptance
            wins the tie.
          </p></ui-card
        >
      </div>
      <p class="muted">
        Server time when loaded: {{ contest()!.serverNow | date: "medium" }}.
        The server decides whether a submission arrives in time.
      </p>
    }`,
})
export class ContestPage implements OnInit {
  api = inject(Api);
  contest = signal<ContestData | null>(null);
  error = signal("");
  async ngOnInit() {
    try {
      this.contest.set(await this.api.request<ContestData>("GET", "/contest"));
    } catch (e) {
      this.error.set(message(e));
    }
  }
}
