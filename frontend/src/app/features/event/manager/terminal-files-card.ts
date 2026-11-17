import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from "@angular/core";
import { HttpErrorResponse } from "@angular/common/http";
import { FormsModule } from "@angular/forms";
import { message } from "../../../core/api";
import { EventApi } from "../../../core/event/event-api";
import {
  RoomBoardRoom,
  TerminalFileEntry,
  TerminalFileList,
} from "../../../core/event/event-models";
import { formatTime } from "../../../core/event/format";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../../shared/ui/ui-card/ui-card";
import { UiConfirmDialog } from "../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiInput } from "../../../shared/ui/ui-input/ui-input";
import { UiProgress } from "../../../shared/ui/ui-progress/ui-progress";
import { UiTextarea } from "../../../shared/ui/ui-textarea/ui-textarea";
import { EventLinuxTerminal } from "../terminal/linux-terminal";
import { BoardLoader } from "./board-loader";
import { EventPackageUpload, formatBytes } from "./package-upload";

/** Same rules as the server (TerminalFiles.pathProblem); the server has the final word. */
const PATH_CHARACTERS = /^[A-Za-z0-9._/-]+$/;
const MAX_PATH = 120;

interface FileDraft {
  /** null: a new file. */
  id: number | null;
  path: string;
  content: string;
}

const EMPTY_DRAFT: FileDraft = { id: null, path: "", content: "" };

/** Every rule a rejected server package broke (the server lists them all at once). */
function problemsOf(error: unknown): string[] {
  const problems: unknown = error instanceof HttpErrorResponse ? error.error?.problems : null;
  return Array.isArray(problems) ? problems.filter((problem): problem is string => typeof problem === "string") : [];
}

export { formatBytes };

export function terminalPathProblem(path: string): string | null {
  if (!path) return "Enter a file path, for example logs/lock.log.";
  if (path.length > MAX_PATH) return `The path is too long (max ${MAX_PATH} characters).`;
  if (!PATH_CHARACTERS.test(path)) return "The path may only contain letters, digits, '.', '_', '-' and '/'.";
  if (path.startsWith("/")) return "The path must be relative (no leading '/').";
  const segments = path.split("/");
  if (segments.includes("..")) return "The path must not contain '..'.";
  if (segments.some((segment) => segment === "" || segment === ".")) return "The path has an empty or '.' part.";
  return null;
}

/**
 * The text files the room's terminal starts with (rules §18): list with sizes against the limits, add by pasting
 * or by reading a .txt/.log file in the browser, edit, delete, and "Test terminal" with exactly these files.
 * Shown only for rooms with the terminal enabled; editable until the final opens.
 */
@Component({
  selector: "event-terminal-files-card",
  imports: [
    EventLinuxTerminal,
    EventPackageUpload,
    FormsModule,
    UiAlert,
    UiButton,
    UiCard,
    UiConfirmDialog,
    UiInput,
    UiProgress,
    UiTextarea,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./terminal-files-card.css",
  templateUrl: "./terminal-files-card.html",
})
export class EventTerminalFilesCard {
  private readonly eventApi = inject(EventApi);

  readonly room = input.required<RoomBoardRoom>();
  /** The board's revision: another person's change reloads the list. */
  readonly revision = input.required<number>();

  protected readonly loader = new BoardLoader<TerminalFileList>(() =>
    this.eventApi.terminalFiles(this.room().id),
  );
  protected readonly list = this.loader.data;
  protected readonly draft = signal<FileDraft | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly deleting = signal<TerminalFileEntry | null>(null);
  protected readonly testing = signal(false);
  protected readonly problems = signal<string[]>([]);
  /** The terminal clock input (a UTC date and time as the browser's datetime-local control writes it). */
  protected readonly clockDraft = signal("");
  protected readonly removingPackage = signal(false);
  protected readonly loadTestFiles = () => this.eventApi.terminalStartFiles(this.room().id);
  protected readonly loadTestBundle = () => this.eventApi.terminalBundle(this.room().id, "manager");
  protected readonly formatBytes = formatBytes;
  protected readonly formatTime = formatTime;
  /** "logs/lock.log" → ["logs/", "lock.log"]: a narrow table breaks the path after a slash, not inside a name. */
  protected readonly pathParts = (path: string) => path.split(/(?<=\/)/);

  protected readonly draftBytes = computed(() => new TextEncoder().encode(this.draft()?.content ?? "").length);
  protected readonly usage = computed(() => {
    const list = this.list();
    if (!list) return "";
    return `${formatBytes(list.totalBytes)} of ${formatBytes(list.maxTotalBytes)} · ${list.files.length} of ${list.maxFiles} files`;
  });

  constructor() {
    effect(() => {
      this.room().id;
      this.revision();
      untracked(() => void this.loader.load());
    });
    // The input shows the stored clock whenever the list changes (another person's change included).
    effect(() => {
      const clock = this.list()?.bundle?.clock ?? null;
      untracked(() => this.clockDraft.set(clock ? clock.slice(0, 19) : ""));
    });
  }

  /** "2026-03-14T09:26:00Z" shown as "2026-03-14 09:26:00". */
  protected formatClock(clock: string): string {
    return clock.slice(0, 19).replace("T", " ");
  }

  protected async saveClock(): Promise<void> {
    const value = this.clockDraft();
    if (!value || this.busy()) return;
    // datetime-local writes minutes ("2026-03-14T09:26") or, with step 1, seconds; the server wants a UTC instant.
    const instant = `${value.length === 16 ? `${value}:00` : value}Z`;
    await this.run(async () => {
      this.loader.data.set(await this.eventApi.setTerminalClock(this.room().id, instant));
      this.notice.set("Terminal clock set. Teams get it the next time their terminal starts.");
    });
  }

  protected async clearClock(): Promise<void> {
    await this.run(async () => {
      this.loader.data.set(await this.eventApi.setTerminalClock(this.room().id, null));
      this.notice.set("The terminals use real time again.");
    });
  }

  protected add(): void {
    this.error.set(null);
    this.notice.set(null);
    this.draft.set({ ...EMPTY_DRAFT });
  }

  protected async edit(file: TerminalFileEntry): Promise<void> {
    this.error.set(null);
    this.notice.set(null);
    try {
      const full = await this.eventApi.terminalFile(this.room().id, file.id);
      this.draft.set({ id: full.id, path: full.path, content: full.content });
    } catch (e) {
      this.error.set(message(e));
    }
  }

  protected cancel(): void {
    this.draft.set(null);
    this.error.set(null);
  }

  protected setPath(path: string): void {
    this.draft.update((draft) => draft && { ...draft, path: path.trim() });
  }

  protected setContent(content: string): void {
    this.draft.update((draft) => draft && { ...draft, content });
  }

  /** Reads a .txt/.log file in the browser; its name becomes logs/<name> unless a path was typed. */
  protected async upload(event: Event): Promise<void> {
    const picker = event.target as HTMLInputElement;
    const file = picker.files?.[0];
    picker.value = "";
    if (!file) return;
    const limit = this.list()?.maxFileBytes ?? 2 * 1024 * 1024;
    if (file.size > limit) {
      this.error.set(`${file.name} is larger than ${formatBytes(limit)}.`);
      return;
    }
    const content = await file.text();
    const suggested = `logs/${file.name.replace(/[^A-Za-z0-9._-]/g, "_")}`;
    this.draft.update((draft) => ({
      id: draft?.id ?? null,
      path: draft?.path || suggested,
      content,
    }));
    this.error.set(null);
  }

  /** Sends the chosen ZIP as the room's server package; a rejected package lists every problem at once. */
  protected async uploadPackage(file: File): Promise<void> {
    const list = this.list();
    if (!file || !list || this.busy()) return;
    this.problems.set([]);
    if (file.size > list.maxBundleUploadBytes) {
      this.error.set(`${file.name} is larger than ${formatBytes(list.maxBundleUploadBytes)}.`);
      return;
    }
    const replacing = list.bundle !== null;
    await this.run(async () => {
      this.loader.data.set(await this.eventApi.uploadTerminalBundle(this.room().id, file));
      this.notice.set(
        `${replacing ? "Replaced" : "Uploaded"} the server package. Teams get it the next time their terminal starts.`,
      );
    });
  }

  protected async confirmRemovePackage(): Promise<void> {
    await this.run(async () => {
      this.loader.data.set(await this.eventApi.deleteTerminalBundle(this.room().id));
      this.notice.set("Removed the server package.");
    });
    this.removingPackage.set(false);
  }

  protected async save(): Promise<void> {
    const draft = this.draft();
    const list = this.list();
    if (!draft || !list || this.busy()) return;
    const problem =
      terminalPathProblem(draft.path) ??
      (this.draftBytes() > list.maxFileBytes ? `The file is too large (max ${formatBytes(list.maxFileBytes)}).` : null);
    if (problem) {
      this.error.set(problem);
      return;
    }
    await this.run(async () => {
      const body = { path: draft.path, content: draft.content };
      const room = this.room().id;
      const saved = draft.id === null
        ? await this.eventApi.createTerminalFile(room, body)
        : await this.eventApi.updateTerminalFile(room, draft.id, body);
      this.loader.data.set(saved);
      this.draft.set(null);
      this.notice.set(`Saved ${draft.path}. Teams get it the next time their terminal starts.`);
    });
  }

  protected async confirmDelete(): Promise<void> {
    const file = this.deleting();
    if (!file) return;
    await this.run(async () => {
      this.loader.data.set(await this.eventApi.deleteTerminalFile(this.room().id, file.id));
      this.notice.set(`Deleted ${file.path}.`);
    });
    this.deleting.set(null);
  }

  private async run(work: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.problems.set([]);
    try {
      await work();
    } catch (e) {
      this.error.set(message(e));
      this.problems.set(problemsOf(e));
    } finally {
      this.busy.set(false);
    }
  }
}
