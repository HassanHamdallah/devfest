import { Component, inject, signal, OnInit } from "@angular/core";
import { ActivatedRoute } from "@angular/router";
import {
  ReactiveFormsModule,
  FormControl,
  FormGroup,
  Validators,
} from "@angular/forms";
import { JsonPipe } from "@angular/common";
import { Api, message } from "../core/api";
import {
  Participant,
  Contest,
  Problem,
  ProblemData,
  Language,
} from "../core/models";
import { UiInput } from "../shared/ui/ui-input/ui-input";
import { UiTextarea } from "../shared/ui/ui-textarea/ui-textarea";
import { UiSelect } from "../shared/ui/ui-select/ui-select";
import { UiButton } from "../shared/ui/ui-button/ui-button";
import { UiCard } from "../shared/ui/ui-card/ui-card";
@Component({
  imports: [
    ReactiveFormsModule,
    JsonPipe,
    UiInput,
    UiTextarea,
    UiButton,
    UiCard,
  ],
  templateUrl: "./admin.html",
})
export class Admin implements OnInit {
  api = inject(Api);
  route = inject(ActivatedRoute);
  section = this.route.snapshot.data["section"] as string;
  error = signal("");
  success = signal("");
  busy = signal(false);
  users = signal<Participant[]>([]);
  problems = signal<Problem[]>([]);
  overview = signal<unknown>(null);
  page = 0;
  editing: number | null = null;
  participant = new FormGroup({
    email: new FormControl("", {
      nonNullable: true,
      validators: [Validators.required, Validators.email],
    }),
    name: new FormControl("", {
      nonNullable: true,
      validators: Validators.required,
    }),
    password: new FormControl("", {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(12)],
    }),
  });
  settings = new FormGroup({
    title: new FormControl("", { nonNullable: true }),
    startAt: new FormControl("", { nonNullable: true }),
    endAt: new FormControl("", { nonNullable: true }),
  });
  problem = new FormGroup({
    title: new FormControl("", { nonNullable: true }),
    statement: new FormControl("", { nonNullable: true }),
    inputFormat: new FormControl("", { nonNullable: true }),
    outputFormat: new FormControl("", { nonNullable: true }),
    constraints: new FormControl("", { nonNullable: true }),
    cpuSeconds: new FormControl("3", { nonNullable: true }),
    memoryKb: new FormControl("524288", { nonNullable: true }),
    examples: new FormControl('[{"input":"2 3\\n","output":"5\\n"}]', {
      nonNullable: true,
    }),
    tests: new FormControl('[{"input":"2 3\\n","output":"5\\n"}]', {
      nonNullable: true,
    }),
    languages: new FormControl("CPP,JAVA,PYTHON", { nonNullable: true }),
  });
  resetPassword = new FormControl("", { nonNullable: true });
  resetUser: number | null = null;
  async ngOnInit() {
    await this.load();
  }
  async action(work: () => Promise<void>) {
    this.busy.set(true);
    this.error.set("");
    this.success.set("");
    try {
      await work();
      this.success.set("Changes saved.");
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.busy.set(false);
    }
  }
  async load() {
    this.busy.set(true);
    try {
      if (this.section === "Participants")
        this.users.set(
          await this.api.request<Participant[]>(
            "GET",
            "/admin/participants?page=" + this.page,
          ),
        );
      if (this.section === "Problems")
        this.problems.set(
          await this.api.request<Problem[]>(
            "GET",
            "/problems?page=" + this.page,
          ),
        );
      if (this.section === "Overview")
        this.overview.set(await this.api.request("GET", "/admin/overview"));
      if (this.section === "Contest Settings") {
        const c = await this.api.request<Contest>("GET", "/contest");
        this.settings.setValue({
          title: c.title,
          startAt: c.startAt,
          endAt: c.endAt,
        });
      }
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.busy.set(false);
    }
  }
  createParticipant() {
    void this.action(async () => {
      await this.api.request(
        "POST",
        "/admin/participants",
        this.participant.getRawValue(),
      );
      this.participant.reset();
      await this.load();
    });
  }
  toggle(u: Participant) {
    void this.action(async () => {
      await this.api.request("PATCH", "/admin/participants/" + u.id, {
        active: !u.active,
      });
      await this.load();
    });
  }
  reset() {
    if (this.resetUser === null) return;
    void this.action(async () => {
      await this.api.request(
        "POST",
        "/admin/participants/" + this.resetUser + "/password",
        { password: this.resetPassword.value },
      );
      this.resetPassword.reset();
      this.resetUser = null;
    });
  }
  saveSettings() {
    void this.action(async () => {
      const s = this.settings.getRawValue();
      await this.api.request("PUT", "/admin/contest", {
        title: s.title,
        startAt: new Date(s.startAt).toISOString(),
        endAt: new Date(s.endAt).toISOString(),
      });
    });
  }
  async edit(id: number) {
    this.error.set("");
    try {
      const p = await this.api.request<ProblemData>(
        "GET",
        "/admin/problems/" + id,
      );
      this.editing = id;
      this.problem.setValue({
        title: p.title,
        statement: p.statement,
        inputFormat: p.inputFormat,
        outputFormat: p.outputFormat,
        constraints: p.constraints,
        cpuSeconds: String(p.cpuSeconds),
        memoryKb: String(p.memoryKb),
        examples: JSON.stringify(p.examples, null, 2),
        tests: JSON.stringify(p.tests, null, 2),
        languages: p.languages.join(","),
      });
    } catch (e) {
      this.error.set(message(e));
    }
  }
  saveProblem() {
    void this.action(async () => {
      const p = this.problem.getRawValue();
      const data: ProblemData = {
        ...p,
        cpuSeconds: Number(p.cpuSeconds),
        memoryKb: Number(p.memoryKb),
        languages: p.languages.split(",").map((x) => x.trim()) as Language[],
        examples: JSON.parse(p.examples),
        tests: JSON.parse(p.tests),
      };
      await this.api.request(
        this.editing ? "PUT" : "POST",
        "/admin/problems" + (this.editing ? "/" + this.editing : ""),
        data,
      );
      await this.load();
    });
  }
  download() {
    void this.action(async () => this.api.downloadResults());
  }
}
