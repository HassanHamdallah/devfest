import { Component, inject, signal, OnInit } from "@angular/core";
import { RouterLink } from "@angular/router";
import { Api, message } from "../core/api";
import { Problem } from "../core/models";
import { UiCard } from "../shared/ui/ui-card/ui-card";
import { UiButton } from "../shared/ui/ui-button/ui-button";
@Component({
  imports: [RouterLink, UiCard, UiButton],
  template: `<header class="page-heading">
      <span class="eyebrow">CHOOSE YOUR CHALLENGE</span>
      <h1>Problems</h1>
      <p>Read the limits, explore the examples, and start coding.</p>
    </header>
    @if (loading()) {
      <p role="status">Loading problems…</p>
    } @else if (error()) {
      <p role="alert">{{ error() }}</p>
      <ui-button (click)="load()">Retry</ui-button>
    } @else {
      <div class="cards">
        @for (p of problems(); track p.id) {
          <ui-card
            ><span class="eyebrow">PROBLEM {{ p.id }}</span>
            <h2>
              <a [routerLink]="['/problems', p.id]">{{ p.title }}</a>
            </h2>
            <p>{{ p.statement }}</p>
            <p class="muted">
              {{ p.cpuSeconds }} seconds · {{ p.memoryKb / 1024 }} MiB ·
              {{ p.languages.length }} languages
            </p>
            <a [routerLink]="['/problems', p.id]">Open workspace →</a></ui-card
          >
        } @empty {
          <p>No problems are available yet.</p>
        }
      </div>
      <div class="actions">
        <ui-button
          variant="secondary"
          [disabled]="page === 0"
          (click)="page = page - 1; load()"
          >Previous</ui-button
        ><ui-button
          variant="secondary"
          [disabled]="problems().length < 50"
          (click)="page = page + 1; load()"
          >Next</ui-button
        >
      </div>
    }`,
})
export class Problems implements OnInit {
  api = inject(Api);
  problems = signal<Problem[]>([]);
  error = signal("");
  loading = signal(true);
  page = 0;
  ngOnInit() {
    void this.load();
  }
  async load() {
    this.loading.set(true);
    this.error.set("");
    try {
      this.problems.set(
        await this.api.request<Problem[]>("GET", "/problems?page=" + this.page),
      );
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.loading.set(false);
    }
  }
}
