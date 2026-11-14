import { Component, inject, signal, OnInit, OnDestroy } from "@angular/core";
import { ActivatedRoute } from "@angular/router";
import { ReactiveFormsModule, FormControl } from "@angular/forms";
import { HttpErrorResponse } from "@angular/common/http";
import { Api, message } from "../core/api";
import {
  Problem,
  Language,
  Draft,
  Submission,
  templates,
  languageNames,
  pending,
} from "../core/models";
import { CodeEditor } from "../shared/monaco";
import { newIdempotencyKey } from "../core/event/pending-request";
import { UiButton } from "../shared/ui/ui-button/ui-button";
import { UiSelect } from "../shared/ui/ui-select/ui-select";
import { UiTextarea } from "../shared/ui/ui-textarea/ui-textarea";
interface LocalDraft extends Draft {
  dirty: boolean;
}
interface PendingRequest {
  key: string;
  kind: "runs" | "submissions";
  body: {
    problemId: number;
    language: Language;
    source: string;
    stdin: string;
  };
}
@Component({
  imports: [ReactiveFormsModule, CodeEditor, UiButton, UiSelect, UiTextarea],
  templateUrl: "./workspace.html",
})
export class Workspace implements OnInit, OnDestroy {
  api = inject(Api);
  route = inject(ActivatedRoute);
  // Keep ownership stable when logout clears the shared session before teardown.
  private readonly participantId = this.api.user()!.id;
  problem = signal<Problem | null>(null);
  source = signal("");
  language = new FormControl<Language>("PYTHON", { nonNullable: true });
  stdin = new FormControl("", { nonNullable: true });
  error = signal("");
  saveState = signal("Loading draft…");
  conflict = signal<Draft | null>(null);
  ready = signal(false);
  busy = signal(false);
  result = signal<Submission | null>(null);
  pendingRequest = signal<PendingRequest | null>(null);
  version = 0;
  dirty = false;
  saving = false;
  timer = 0;
  retry = 0;
  poll = 0;
  destroyed = false;
  private switching = false;
  private activeLanguage: Language = "PYTHON";
  private storageFailed = false;
  get options() {
    return (this.problem()?.languages || []).map((value) => ({
      value,
      label: languageNames[value],
    }));
  }
  get editorLanguage() {
    return { CPP: "cpp", JAVA: "java", PYTHON: "python" }[this.activeLanguage];
  }
  private key() {
    return (
      "devfest.draft." +
      this.participantId +
      ".1." +
      this.problem()!.id +
      "." +
      this.activeLanguage
    );
  }
  private requestKey() {
    return "devfest.pending." + this.participantId + "." + this.problem()!.id;
  }
  async ngOnInit() {
    try {
      const p = await this.api.request<Problem>(
        "GET",
        "/problems/" + this.route.snapshot.paramMap.get("id"),
      );
      if (this.destroyed) return;
      this.problem.set(p);
      this.activeLanguage = p.languages.includes("PYTHON")
        ? "PYTHON"
        : p.languages[0];
      this.language.setValue(this.activeLanguage);
      this.pendingRequest.set(this.read<PendingRequest>(this.requestKey()));
      await this.loadDraft();
      this.retry = window.setInterval(() => {
        if (this.dirty && !this.conflict()) void this.save();
      }, 5000);
    } catch (e) {
      this.error.set(message(e));
    }
  }
  private read<T>(key: string): T | null {
    try {
      return JSON.parse(localStorage.getItem(key) || "null") as T | null;
    } catch {
      return null;
    }
  }
  private persist() {
    try {
      localStorage.setItem(
        this.key(),
        JSON.stringify({
          source: this.source(),
          version: this.version,
          updatedAt: new Date().toISOString(),
          dirty: this.dirty,
        }),
      );
      this.storageFailed = false;
    } catch {
      this.storageFailed = true;
      this.saveState.set(
        "Local storage unavailable. Keep this tab open until saved online.",
      );
    }
  }
  async loadDraft() {
    this.ready.set(false);
    this.conflict.set(null);
    const local = this.read<LocalDraft>(this.key());
    try {
      const server = await this.api.request<Draft>(
        "GET",
        "/drafts/" + this.problem()!.id + "/" + this.activeLanguage,
      );
      if (this.destroyed) return;
      if (local?.dirty) {
        this.source.set(local.source);
        this.version = local.version;
        this.dirty = true;
        if (local.source === server.source) {
          this.version = server.version;
          this.dirty = false;
        } else if (local.version !== server.version) this.conflict.set(server);
      } else {
        this.source.set(
          server.version ? server.source : templates[this.activeLanguage],
        );
        this.version = server.version;
        this.dirty = false;
      }
      this.saveState.set(
        this.conflict()
          ? "Draft conflict: your local work is preserved."
          : this.dirty
            ? "Local changes recovered"
            : "Saved",
      );
    } catch (e) {
      if (local) {
        this.source.set(local.source);
        this.version = local.version;
        this.dirty = local.dirty;
        this.saveState.set("Offline copy recovered. Reconnecting…");
      } else {
        this.source.set(templates[this.activeLanguage]);
        this.version = 0;
        this.dirty = false;
        this.saveState.set(
          "No saved draft could be loaded. New edits will be kept locally.",
        );
      }
      this.error.set(message(e));
    }
    this.ready.set(true);
  }
  edit(value: string) {
    if (value === this.source()) return;
    this.source.set(value);
    this.dirty = true;
    this.saveState.set("Unsaved changes");
    clearTimeout(this.timer);
    this.timer = window.setTimeout(() => {
      this.persist();
      void this.save();
    }, 700);
  }
  async save() {
    if (
      !this.dirty ||
      this.saving ||
      this.conflict() ||
      !this.ready() ||
      this.destroyed
    )
      return;
    this.saving = true;
    this.persist();
    this.saveState.set("Saving…");
    const source = this.source();
    const key = this.key();
    try {
      const saved = await this.api.request<Draft>(
        "PUT",
        "/drafts/" + this.problem()!.id + "/" + this.activeLanguage,
        { source, version: this.version },
      );
      if (key !== this.key()) return;
      this.version = saved.version;
      this.dirty = this.source() !== source;
      this.persist();
      if (!this.storageFailed)
        this.saveState.set(this.dirty ? "More changes waiting…" : "Saved");
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        try {
          this.conflict.set(
            await this.api.request<Draft>(
              "GET",
              "/drafts/" + this.problem()!.id + "/" + this.activeLanguage,
            ),
          );
        } catch {}
        this.saveState.set("Draft conflict. Your local work is kept.");
      } else {
        this.saveState.set("Save failed. Local changes kept; retrying…");
      }
      this.persist();
    } finally {
      this.saving = false;
    }
  }
  async switchLanguage() {
    if (this.switching || this.saving) {
      this.language.setValue(this.activeLanguage);
      return;
    }
    this.switching = true;
    clearTimeout(this.timer);
    this.persist();
    this.activeLanguage = this.language.value;
    await this.loadDraft();
    this.switching = false;
  }
  async keepLocal() {
    const server = this.conflict();
    if (!server) return;
    this.version = server.version;
    this.conflict.set(null);
    this.dirty = true;
    await this.save();
  }
  useServer() {
    const server = this.conflict();
    if (!server) return;
    try {
      localStorage.setItem(this.key() + ".backup." + Date.now(), this.source());
    } catch {
      this.error.set(
        "Cannot preserve the local backup. Copy your code before replacing it.",
      );
      return;
    }
    this.source.set(server.source);
    this.version = server.version;
    this.dirty = false;
    this.conflict.set(null);
    this.persist();
    this.saveState.set(
      "Server draft loaded. Previous local code is kept as a browser backup.",
    );
  }
  async execute(kind: "runs" | "submissions") {
    if (this.busy() || !this.ready()) return;
    if (!this.pendingRequest()) {
      const r: PendingRequest = {
        key: newIdempotencyKey(),
        kind,
        body: {
          problemId: this.problem()!.id,
          language: this.activeLanguage,
          source: this.source(),
          stdin: kind === "runs" ? this.stdin.value : "",
        },
      };
      this.pendingRequest.set(r);
      try {
        localStorage.setItem(this.requestKey(), JSON.stringify(r));
      } catch {
        this.error.set(
          "Cannot safely retain this request for retry. Free browser storage and try again.",
        );
        this.pendingRequest.set(null);
        return;
      }
    }
    await this.sendPending();
  }
  async sendPending() {
    const r = this.pendingRequest();
    if (!r || this.busy()) return;
    this.busy.set(true);
    this.error.set("");
    this.persist();
    try {
      const response = await this.api.request<{ id: string }>(
        "POST",
        "/" + r.kind,
        r.body,
        { "Idempotency-Key": r.key },
      );
      localStorage.removeItem(this.requestKey());
      this.pendingRequest.set(null);
      await this.track(response.id);
    } catch (e) {
      this.error.set(message(e));
      if (
        e instanceof HttpErrorResponse &&
        [400, 403, 409, 422, 429].includes(e.status)
      ) {
        localStorage.removeItem(this.requestKey());
        this.pendingRequest.set(null);
      }
    } finally {
      this.busy.set(false);
    }
  }
  async track(id: string) {
    clearTimeout(this.poll);
    try {
      const result = await this.api.request<Submission>(
        "GET",
        "/submissions/" + id,
      );
      if (this.destroyed) return;
      this.result.set(result);
      if (pending(result.state))
        this.poll = window.setTimeout(() => void this.track(id), 2000);
    } catch (e) {
      this.error.set(message(e));
      if (!this.destroyed)
        this.poll = window.setTimeout(() => void this.track(id), 5000);
    }
  }
  label(state: string) {
    return state.replaceAll("_", " ");
  }
  ngOnDestroy() {
    this.destroyed = true;
    clearTimeout(this.timer);
    clearInterval(this.retry);
    clearTimeout(this.poll);
    if (this.problem() && this.ready()) this.persist();
  }
}
