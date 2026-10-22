import { Component, inject, signal, OnInit, OnDestroy } from "@angular/core";
import { DatePipe } from "@angular/common";
import { ReactiveFormsModule, FormControl } from "@angular/forms";
import { Api, message } from "../core/api";
import { Submission, pending } from "../core/models";
import { UiButton } from "../shared/ui/ui-button/ui-button";
import { UiSelect } from "../shared/ui/ui-select/ui-select";
@Component({
  imports: [DatePipe, ReactiveFormsModule, UiButton, UiSelect],
  template: `<header class="page-heading">
      <span class="eyebrow">YOUR PROGRESS</span>
      <h1>
        {{
          api.user()?.role === "ADMIN" ? "All submissions" : "My submissions"
        }}
      </h1>
      <p>Each attempt keeps the exact code you sent.</p>
    </header>
    <div class="actions">
      <ui-select
        label="Status"
        [formControl]="filter"
        [options]="filters"
      /><ui-button variant="secondary" (click)="load()">Refresh</ui-button>
    </div>
    @if (error()) {
      <p role="alert">{{ error() }}</p>
    }
    @if (loading()) {
      <p role="status">Loading submissions…</p>
    }
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Received</th>
            <th>Problem</th>
            <th>Action</th>
            <th>Language</th>
            <th>Status</th>
            <th>Details</th>
          </tr>
        </thead>
        <tbody>
          @for (s of rows(); track s.id) {
            <tr>
              <td>{{ s.receivedAt | date: "mediumTime" }}</td>
              <td>{{ s.problemId }}</td>
              <td>{{ s.kind }}</td>
              <td>{{ s.language }}</td>
              <td>
                <span
                  class="verdict"
                  [class.accepted]="s.state === 'ACCEPTED'"
                  >{{ label(s.state) }}</span
                >
              </td>
              <td>
                <ui-button variant="ghost" size="sm" (click)="open(s.id)"
                  >View</ui-button
                >
                @if (
                  api.user()?.role === "ADMIN" &&
                  s.kind === "SUBMIT" &&
                  !pending(s.state)
                ) {
                  <ui-button
                    variant="secondary"
                    size="sm"
                    (click)="rejudge(s.id)"
                    >Rejudge</ui-button
                  >
                }
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="6">No submissions yet.</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    <div class="actions">
      <ui-button
        variant="secondary"
        [disabled]="page === 0"
        (click)="page = page - 1; load()"
        >Previous</ui-button
      ><ui-button
        variant="secondary"
        [disabled]="rows().length < 50"
        (click)="page = page + 1; load()"
        >Next</ui-button
      >
    </div>
    @if (detail(); as d) {
      <section class="detail">
        <h2>{{ d.kind }} · {{ label(d.state) }}</h2>
        <p>
          {{ d.runtime }} · Problem version {{ d.problemVersion }} · Evaluation
          {{ d.generation }} · {{ d.passed }} / {{ d.total }} tests
        </p>
        <pre>{{ d.source }}</pre>
        @if (d.kind === "RUN") {
          <h3>Standard output</h3>
          <pre>{{ d.stdout || "(empty)" }}</pre>
          <h3>Errors</h3>
          <pre>{{ d.stderr || d.compileOutput || "(none)" }}</pre>
        }
        <p class="muted">{{ history() }}</p>
      </section>
    }`,
})
export class Submissions implements OnInit, OnDestroy {
  api = inject(Api);
  rows = signal<Submission[]>([]);
  detail = signal<Submission | null>(null);
  history = signal("");
  error = signal("");
  loading = signal(true);
  pending = pending;
  page = 0;
  filter = new FormControl("", { nonNullable: true });
  filters = [
    "",
    "QUEUED",
    "RUNNING",
    "ACCEPTED",
    "WRONG_ANSWER",
    "COMPILATION_ERROR",
    "RUNTIME_ERROR",
    "TIME_LIMIT_EXCEEDED",
    "MEMORY_LIMIT_EXCEEDED",
    "SYSTEM_ERROR",
  ].map((x) => ({
    value: x,
    label: x ? x.replaceAll("_", " ") : "All statuses",
  }));
  timer = 0;
  label(s: string) {
    return s.replaceAll("_", " ");
  }
  ngOnInit() {
    void this.load();
    this.timer = window.setInterval(() => {
      if (this.rows().some((s) => pending(s.state))) void this.load(false);
    }, 3000);
  }
  ngOnDestroy() {
    clearInterval(this.timer);
  }
  async load(spinner = true) {
    if (spinner) this.loading.set(true);
    try {
      this.rows.set(
        await this.api.request<Submission[]>(
          "GET",
          "/submissions?page=" + this.page + "&state=" + this.filter.value,
        ),
      );
      this.error.set("");
      if (this.detail()) await this.open(this.detail()!.id);
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.loading.set(false);
    }
  }
  async open(id: string) {
    try {
      this.detail.set(
        await this.api.request<Submission>("GET", "/submissions/" + id),
      );
      const h = await this.api.request<{ generation: number; state: string }[]>(
        "GET",
        "/submissions/" + id + "/history",
      );
      this.history.set(
        "Evaluation history: " +
          h.map((x) => x.generation + " " + this.label(x.state)).join(" → "),
      );
    } catch (e) {
      this.error.set(message(e));
    }
  }
  async rejudge(id: string) {
    try {
      await this.api.request(
        "POST",
        "/admin/submissions/" + id + "/rejudge",
        {},
      );
      await this.load();
    } catch (e) {
      this.error.set(message(e));
    }
  }
}
