import { Component, inject, signal, OnInit } from "@angular/core";
import { DatePipe } from "@angular/common";
import { Api, message } from "../core/api";
import { Score } from "../core/models";
import { UiButton } from "../shared/ui/ui-button/ui-button";
@Component({
  imports: [DatePipe, UiButton],
  template: `<header class="page-heading">
      <span class="eyebrow">EVERY SOLVE COUNTS</span>
      <h1>Scoreboard</h1>
      <p>
        One point per problem. Equal solve counts are ordered by the receipt
        time of the last contributing accepted solution.
      </p>
      <ui-button variant="secondary" (click)="load()">Refresh</ui-button>
    </header>
    @if (error()) {
      <p role="alert">{{ error() }}</p>
    }
    @if (loading()) {
      <p role="status">Loading scores…</p>
    } @else {
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Rank</th>
              <th>Participant</th>
              <th>Solved</th>
              <th>Last contributing acceptance</th>
            </tr>
          </thead>
          <tbody>
            @for (s of scores(); track s.userId) {
              <tr>
                <td>{{ s.rank }}</td>
                <td>{{ s.name }}</td>
                <td>{{ s.solved }}</td>
                <td>
                  {{ s.lastAccepted ? (s.lastAccepted | date: "medium") : "—" }}
                </td>
              </tr>
            } @empty {
              <tr>
                <td colspan="4">No participants yet.</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }`,
})
export class Scoreboard implements OnInit {
  api = inject(Api);
  scores = signal<Score[]>([]);
  error = signal("");
  loading = signal(true);
  ngOnInit() {
    void this.load();
  }
  async load() {
    this.loading.set(true);
    try {
      this.scores.set(await this.api.request<Score[]>("GET", "/scoreboard"));
      this.error.set("");
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.loading.set(false);
    }
  }
}
