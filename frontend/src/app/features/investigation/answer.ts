import {
  Component,
  inject,
  signal,
  OnInit,
  OnDestroy,
  HostListener,
} from "@angular/core";
import { ActivatedRoute, RouterLink } from "@angular/router";
import { FormsModule } from "@angular/forms";
import { DatePipe } from "@angular/common";
import { Api, message } from "../../core/api";
import { UiButton } from "../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../shared/ui/ui-card/ui-card";
import { UiTextarea } from "../../shared/ui/ui-textarea/ui-textarea";
import {
  Challenge,
  Draft,
  INV,
  Receipt,
  Report,
  Settings,
  Submission,
} from "./models";
interface LocalDraft {
  report: Report;
  version: number;
}
interface PendingSubmission {
  key: string;
  report: Report;
}
function isReport(value: unknown): value is Report {
  return (
    typeof value === "object" &&
    value !== null &&
    "conclusion" in value &&
    typeof value.conclusion === "string" &&
    "reasoning" in value &&
    typeof value.reasoning === "string" &&
    "evidence" in value &&
    typeof value.evidence === "string"
  );
}
function isLocalDraft(value: unknown): value is LocalDraft {
  return (
    typeof value === "object" &&
    value !== null &&
    "report" in value &&
    isReport(value.report) &&
    "version" in value &&
    typeof value.version === "number" &&
    Number.isInteger(value.version) &&
    value.version >= 0
  );
}
function isPending(value: unknown): value is PendingSubmission {
  return (
    typeof value === "object" &&
    value !== null &&
    "report" in value &&
    isReport(value.report) &&
    "key" in value &&
    typeof value.key === "string" &&
    /^[0-9a-f-]{36}$/i.test(value.key)
  );
}
@Component({
  imports: [RouterLink, FormsModule, DatePipe, UiButton, UiCard, UiTextarea],
  templateUrl: "./answer.html",
  styleUrl: "./investigation.css",
})
export class InvestigationAnswer implements OnInit, OnDestroy {
  api = inject(Api);
  route = inject(ActivatedRoute);
  room = Number(this.route.snapshot.paramMap.get("room"));
  id = Number(this.route.snapshot.paramMap.get("id"));
  challenge = signal<Challenge | null>(null);
  settings = signal<Settings | null>(null);
  history = signal<Submission[]>([]);
  receipt = signal<Receipt | null>(null);
  report: Report = { conclusion: "", reasoning: "", evidence: "" };
  version = 0;
  locked = signal(false);
  dirty = signal(false);
  busy = signal(false);
  loading = signal(true);
  saved = signal("");
  error = signal("");
  pending = signal<PendingSubmission | null>(null);
  page = 0;
  recoveryWarning = signal("");
  private timer: ReturnType<typeof setTimeout> | undefined;
  private owner = this.api.user()?.id;
  private get storageKey() {
    return `devfest.investigation.${this.owner}.${this.id}`;
  }
  get canEdit() {
    return (
      !!this.challenge() &&
      !this.loading() &&
      !this.locked() &&
      this.challenge()?.availability === "OPEN"
    );
  }
  get alreadySubmitted() {
    return this.history().some(
      (s) =>
        s.latest && JSON.stringify(s.report) === JSON.stringify(this.report),
    );
  }
  ngOnInit() {
    void this.load();
  }
  ngOnDestroy() {
    clearTimeout(this.timer);
  }
  @HostListener("window:beforeunload", ["$event"]) warn(
    event: BeforeUnloadEvent,
  ) {
    if (this.dirty()) event.preventDefault();
  }
  async load() {
    this.loading.set(true);
    this.error.set("");
    try {
      const [challenges, settings] = await Promise.all([
        this.api.request<Challenge[]>(
          "GET",
          INV + "/rooms/" + this.room + "/challenges",
        ),
        this.api.request<Settings>("GET", INV + "/settings"),
      ]);
      const challenge = challenges.find((c) => c.id === this.id);
      if (!challenge)
        throw new Error("This challenge is not available to your team.");
      this.challenge.set(challenge);
      this.settings.set(settings);
      const draft = await this.api.request<Draft>(
        "GET",
        INV + "/challenges/" + this.id + "/draft",
      );
      this.report = draft.report;
      this.version = draft.version;
      this.locked.set(draft.locked);
      this.saved.set(
        draft.updatedAt
          ? "Saved on server " + new Date(draft.updatedAt).toLocaleString()
          : "No server draft yet.",
      );
      const local = this.readLocal(this.storageKey);
      if (
        isLocalDraft(local) &&
        !draft.locked &&
        JSON.stringify(local.report) !== JSON.stringify(draft.report)
      ) {
        this.report = local.report;
        this.version = local.version;
        this.dirty.set(true);
        this.saved.set("Recovered local edits; save to the server.");
      }
      const pending = this.readLocal(this.storageKey + ".pending");
      this.pending.set(isPending(pending) ? pending : null);
      await this.loadHistory();
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.loading.set(false);
    }
  }
  private readLocal(key: string): unknown {
    try {
      const value = localStorage.getItem(key);
      return value ? JSON.parse(value) : null;
    } catch {
      this.recoveryWarning.set(
        "Local recovery data could not be read. The server draft is still available.",
      );
      return null;
    }
  }
  private writeLocal(key: string, value: unknown): boolean {
    try {
      localStorage.setItem(key, JSON.stringify(value));
      return true;
    } catch {
      this.recoveryWarning.set(
        "Local recovery storage is unavailable. Keep this page open and save drafts to the server.",
      );
      return false;
    }
  }
  private removeLocal(key: string) {
    try {
      localStorage.removeItem(key);
    } catch {
      this.recoveryWarning.set(
        "Could not clear local recovery data. Check the server history when returning.",
      );
    }
  }
  changed() {
    this.dirty.set(true);
    this.saved.set("Unsaved edits");
    this.writeLocal(this.storageKey, {
      report: this.report,
      version: this.version,
    });
    clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.save(), 1000);
  }
  async save() {
    if (this.busy() || !this.canEdit || !this.dirty()) return;
    this.busy.set(true);
    this.error.set("");
    const snapshot = { ...this.report };
    try {
      const draft = await this.api.request<Draft>(
        "PUT",
        INV + "/challenges/" + this.id + "/draft",
        { report: snapshot, version: this.version },
      );
      this.version = draft.version;
      this.dirty.set(JSON.stringify(snapshot) !== JSON.stringify(this.report));
      this.writeLocal(this.storageKey, {
        report: this.report,
        version: this.version,
      });
      this.saved.set(
        this.dirty()
          ? "Unsaved edits"
          : "Saved on server " + new Date(draft.updatedAt!).toLocaleString(),
      );
    } catch (e) {
      this.error.set(message(e));
      this.saved.set("Not saved on server; your edits are kept locally.");
    } finally {
      this.busy.set(false);
    }
  }
  async loadServer() {
    if (this.busy()) return;
    this.busy.set(true);
    clearTimeout(this.timer);
    try {
      if (
        this.dirty() &&
        !this.writeLocal(this.storageKey + ".backup", {
          report: this.report,
          version: this.version,
        })
      )
        throw new Error(
          "Copy your edits before loading the server draft; a local backup could not be saved.",
        );
      const draft = await this.api.request<Draft>(
        "GET",
        INV + "/challenges/" + this.id + "/draft",
      );
      this.report = draft.report;
      this.version = draft.version;
      this.locked.set(draft.locked);
      this.dirty.set(false);
      this.removeLocal(this.storageKey);
      this.error.set("");
      this.saved.set(
        "Loaded server draft. Unsaved edits, if any, were backed up locally.",
      );
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.busy.set(false);
    }
  }
  async submit() {
    if (this.busy()) return;
    clearTimeout(this.timer);
    this.busy.set(true);
    this.error.set("");
    try {
      const pending = this.pending() || {
        key: crypto.randomUUID(),
        report: { ...this.report },
      };
      // Persist the request key and exact payload before sending; retries survive reload/session renewal.
      if (!this.writeLocal(this.storageKey + ".pending", pending))
        throw new Error(
          "Could not preserve the submission retry key. Enable browser storage before submitting. Your server draft is safe.",
        );
      this.pending.set(pending);
      const receipt = await this.api.request<Receipt>(
        "POST",
        INV + "/challenges/" + this.id + "/submissions",
        pending.report,
        { "Idempotency-Key": pending.key },
      );
      this.receipt.set(receipt);
      this.pending.set(null);
      this.removeLocal(this.storageKey + ".pending");
      if (this.challenge()?.kind === "FINAL") this.locked.set(true);
      if (JSON.stringify(this.report) === JSON.stringify(pending.report)) {
        this.dirty.set(false);
        this.removeLocal(this.storageKey);
      }
      this.saved.set("Submission committed on server.");
      await this.loadHistory();
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.busy.set(false);
    }
  }
  discardRetry() {
    this.pending.set(null);
    this.removeLocal(this.storageKey + ".pending");
    this.error.set(
      "Retry request cleared. Check submission history before submitting another answer.",
    );
  }
  async loadHistory() {
    const all = await this.api.request<Submission[]>(
      "GET",
      INV + "/rooms/" + this.room + "/submissions?page=" + this.page,
    );
    this.history.set(all.filter((s) => s.challengeId === this.id));
  }
}
