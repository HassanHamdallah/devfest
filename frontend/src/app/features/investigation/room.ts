import { Component, inject, signal, OnInit } from "@angular/core";
import { ActivatedRoute, RouterLink } from "@angular/router";
import { FormsModule } from "@angular/forms";
import { DatePipe, JsonPipe } from "@angular/common";
import { Api, message } from "../../core/api";
import { UiButton } from "../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../shared/ui/ui-card/ui-card";
import { UiInput } from "../../shared/ui/ui-input/ui-input";
import { UiTextarea } from "../../shared/ui/ui-textarea/ui-textarea";
import { UiSelect } from "../../shared/ui/ui-select/ui-select";
import {
  Challenge,
  INV,
  Result,
  Room,
  Settings,
  Submission,
  Team,
} from "./models";
@Component({
  imports: [
    RouterLink,
    FormsModule,
    DatePipe,
    JsonPipe,
    UiButton,
    UiCard,
    UiInput,
    UiTextarea,
    UiSelect,
  ],
  templateUrl: "./room.html",
  styleUrl: "./investigation.css",
})
export class InvestigationRoom implements OnInit {
  api = inject(Api);
  id = Number(inject(ActivatedRoute).snapshot.paramMap.get("id"));
  room = signal<Room | null>(null);
  teams = signal<Team[]>([]);
  challenges = signal<Challenge[]>([]);
  submissions = signal<Submission[]>([]);
  settings = signal<Settings | null>(null);
  results = signal<Result[]>([]);
  error = signal("");
  success = signal("");
  busy = signal(false);
  page = 0;
  editing: number | null = null;
  version = 0;
  selected = signal<Submission | null>(null);
  scoreHistory = signal<unknown[]>([]);
  points = "0";
  feedback = "";
  form = this.blank();
  states = [
    { value: "DRAFT", label: "Draft — hidden" },
    { value: "OPEN", label: "Open during scheduled times" },
    { value: "CLOSED", label: "Closed" },
  ];
  kinds = [
    { value: "ANSWER", label: "Ordinary answer" },
    { value: "FINAL", label: "Final investigation" },
  ];
  get reviewer() {
    return this.api.user()?.role !== "MEMBER";
  }
  blank() {
    return {
      title: "",
      instructions: "",
      evidenceLinks: "",
      answerFormat: "Text answer",
      rubric: "",
      maxScore: "100",
      kind: "ANSWER",
      state: "DRAFT",
      opensAt: "",
      closesAt: "",
    };
  }
  ngOnInit() {
    void this.action(() => this.load());
  }
  async action(work: () => Promise<unknown>, success = "") {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set("");
    this.success.set("");
    try {
      await work();
      this.success.set(success);
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.busy.set(false);
    }
  }
  async load() {
    const [rooms, teams, challenges, submissions, settings] = await Promise.all(
      [
        this.api.request<Room[]>("GET", INV + "/rooms"),
        this.api.request<Team[]>("GET", INV + "/teams"),
        this.api.request<Challenge[]>(
          "GET",
          INV + "/rooms/" + this.id + "/challenges",
        ),
        this.api.request<Submission[]>(
          "GET",
          INV + "/rooms/" + this.id + "/submissions?page=" + this.page,
        ),
        this.api.request<Settings>("GET", INV + "/settings"),
      ],
    );
    this.room.set(rooms.find((r) => r.id === this.id) || null);
    this.teams.set(teams.filter((t) => t.roomId === this.id));
    this.challenges.set(challenges);
    this.submissions.set(submissions);
    this.settings.set(settings);
    this.results.set(
      this.reviewer || settings.resultsPublished
        ? await this.api.request<Result[]>(
            "GET",
            INV + "/rooms/" + this.id + "/results",
          )
        : [],
    );
  }
  edit(c: Challenge) {
    this.editing = c.id;
    this.version = c.version;
    this.form = {
      title: c.title,
      instructions: c.instructions,
      evidenceLinks: c.evidenceLinks.join("\n"),
      answerFormat: c.answerFormat,
      rubric: c.rubric,
      maxScore: String(c.maxScore),
      kind: c.kind,
      state: c.state,
      opensAt: c.opensAt,
      closesAt: c.closesAt,
    };
  }
  newChallenge() {
    this.editing = null;
    this.version = 0;
    this.form = this.blank();
  }
  saveChallenge() {
    void this.action(async () => {
      if (
        ![this.form.opensAt, this.form.closesAt].every((value) =>
          /(Z|[+-]\d{2}:\d{2})$/i.test(value),
        )
      )
        throw new Error("Include a timezone offset or Z in both times.");
      const body = {
        ...this.form,
        evidenceLinks: this.form.evidenceLinks
          .split("\n")
          .map((s) => s.trim())
          .filter(Boolean),
        maxScore: Number(this.form.maxScore),
        version: this.version,
        opensAt: new Date(this.form.opensAt).toISOString(),
        closesAt: new Date(this.form.closesAt).toISOString(),
      };
      const c = await this.api.request<Challenge>(
        this.editing ? "PUT" : "POST",
        INV +
          "/rooms/" +
          this.id +
          "/challenges" +
          (this.editing ? "/" + this.editing : ""),
        body,
      );
      this.edit(c);
      await this.load();
    }, "Challenge saved. Results are private until republished.");
  }
  review(s: Submission) {
    void this.action(async () => {
      const current = await this.api.request<Submission>(
        "GET",
        INV + "/submissions/" + s.id,
      );
      this.selected.set(current);
      this.points = String(current.points ?? 0);
      this.feedback = current.feedback || "";
      this.scoreHistory.set(
        await this.api.request<unknown[]>(
          "GET",
          INV + "/submissions/" + s.id + "/scores",
        ),
      );
    });
  }
  score() {
    const s = this.selected();
    if (!s) return;
    void this.action(async () => {
      await this.api.request("PUT", INV + "/submissions/" + s.id + "/score", {
        points: Number(this.points),
        feedback: this.feedback,
        version: s.scoreVersion,
      });
      this.selected.set(
        await this.api.request<Submission>("GET", INV + "/submissions/" + s.id),
      );
      this.scoreHistory.set(
        await this.api.request<unknown[]>(
          "GET",
          INV + "/submissions/" + s.id + "/scores",
        ),
      );
      await this.load();
    }, "Score saved. Results are private until republished.");
  }
  paginate(delta: number) {
    this.page = Math.max(0, this.page + delta);
    void this.action(() => this.load());
  }
  teamName(id: number) {
    return this.teams().find((t) => t.id === id)?.name || "Team " + id;
  }
  challengeName(id: number) {
    return (
      this.challenges().find((c) => c.id === id)?.title || "Challenge " + id
    );
  }
  rubric(s: Submission) {
    try {
      const snapshot: unknown = JSON.parse(s.challengeSnapshot);
      return typeof snapshot === "object" &&
        snapshot !== null &&
        "rubric" in snapshot
        ? String(snapshot.rubric)
        : "";
    } catch {
      return "Could not read the saved rubric.";
    }
  }
}
