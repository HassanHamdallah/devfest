import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  ViewEncapsulation,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from "@angular/core";
import { Terminal } from "@xterm/xterm";
import { message } from "../../../core/api";
import { TerminalStartFiles } from "../../../core/event/event-models";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiConfirmDialog } from "../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiProgress } from "../../../shared/ui/ui-progress/ui-progress";
import { LinuxSession, TERMINAL_COLS, TERMINAL_ROWS, TerminalBundle } from "./linux-session";
import { terminalAssets } from "./terminal-assets";

type TerminalState = "downloading" | "booting" | "ready" | "ended" | "failed";

const BANNER = "\x1b[1mYour files are in ~/logs. There is no internet in this terminal.\x1b[0m\r\n";
const SERVER_BANNER = "\x1b[1mRami's server is mounted. There is no internet in this terminal.\x1b[0m\r\n";
const TIME_UP = "\r\n\x1b[1;31mTime is up.\x1b[0m The terminal no longer accepts input.\r\n";
const MB = 1024 * 1024;

/**
 * A Linux terminal running entirely in this browser tab (v86 + xterm.js). It starts as soon as it is created:
 * downloads the emulator (unless preloaded), boots, places the room's files under ~ and shows the shell. Nothing
 * runs on the server. Loaded only through @defer, so pages that never open a terminal never load this code.
 */
@Component({
  selector: "event-linux-terminal",
  imports: [UiAlert, UiButton, UiConfirmDialog, UiProgress],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // xterm.js builds its own DOM, which emulated encapsulation cannot reach; every own rule is under .linux-terminal.
  encapsulation: ViewEncapsulation.None,
  styleUrls: ["../../../../../node_modules/@xterm/xterm/css/xterm.css", "./linux-terminal.css"],
  templateUrl: "./linux-terminal.html",
})
export class EventLinuxTerminal {
  /** Loads the files the terminal starts with (and again on every restart). */
  readonly loadFiles = input.required<() => Promise<TerminalStartFiles>>();
  /** Downloads the room's server package; needed only when the files response says the room has one. */
  readonly loadBundle = input<(() => Promise<ArrayBuffer>) | null>(null);
  /** The round is over: the terminal stops and says "Time is up" (Restart works again if time is added). */
  readonly ended = input(false);
  /** Shows "Use selection as technical output" (the team's answer form is next to the terminal). */
  readonly offerOutput = input(false);
  readonly useAsOutput = output<string>();

  protected readonly state = signal<TerminalState>("downloading");
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly bootSeconds = signal<number | null>(null);
  protected readonly confirmRestart = signal(false);
  protected readonly downloaded = computed(() => (terminalAssets.loadedBytes() / MB).toFixed(1));
  protected readonly downloadTotal = (terminalAssets.totalBytes / MB).toFixed(1);
  protected readonly loadedBytes = terminalAssets.loadedBytes;
  protected readonly totalBytes = terminalAssets.totalBytes;
  protected readonly running = computed(() => this.state() === "ready");

  private readonly screen = viewChild.required<ElementRef<HTMLElement>>("screen");
  private terminal: Terminal | null = null;
  private session: LinuxSession | null = null;
  /** Increments on every (re)start so a superseded boot never takes over the screen. */
  private generation = 0;
  private pending: number[] = [];
  private flushQueued = false;
  private readonly warnBeforeLeaving = (event: BeforeUnloadEvent) => {
    event.preventDefault();
    event.returnValue = "";
  };

  constructor() {
    afterNextRender(() => {
      this.terminal = this.createTerminal();
      void this.start();
    });
    effect(() => {
      if (this.ended()) untracked(() => void this.end());
    });
    effect(() => {
      const active = this.state() === "booting" || this.state() === "ready";
      if (active) window.addEventListener("beforeunload", this.warnBeforeLeaving);
      else window.removeEventListener("beforeunload", this.warnBeforeLeaving);
    });
    inject(DestroyRef).onDestroy(() => {
      this.generation++;
      window.removeEventListener("beforeunload", this.warnBeforeLeaving);
      void this.session?.destroy();
      this.session = null;
      this.terminal?.dispose();
      this.terminal = null;
    });
  }

  protected async restart(): Promise<void> {
    this.confirmRestart.set(false);
    if (this.ended()) return;
    await this.session?.destroy();
    this.session = null;
    this.terminal?.reset();
    await this.start();
  }

  protected async copySelection(): Promise<void> {
    const text = this.selection();
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      this.notice.set("Copied.");
    } catch {
      this.notice.set("Copying is blocked in this browser. Right-click the selection and choose Copy.");
    }
  }

  protected useSelection(): void {
    const text = this.selection();
    if (!text) return;
    this.useAsOutput.emit(text);
    this.notice.set("Pasted into Technical output. Review it and submit when ready.");
  }

  /** The selected terminal text without the padding xterm adds at line ends; null (with a hint) when empty. */
  private selection(): string | null {
    const text = (this.terminal?.getSelection() ?? "")
      .split("\n")
      .map((line) => line.replace(/\s+$/, ""))
      .join("\n")
      .trim();
    if (!text) this.notice.set("Select text in the terminal first (drag with the mouse).");
    return text || null;
  }

  private async start(): Promise<void> {
    const generation = ++this.generation;
    const current = () => generation === this.generation;
    const started = performance.now();
    this.error.set(null);
    this.notice.set(null);
    this.bootSeconds.set(null);
    this.state.set("downloading");
    // After "Time is up" the admin may add time; a restart then takes input again.
    if (this.terminal) this.terminal.options.disableStdin = false;
    try {
      const [Emulator, buffers, files] = await Promise.all([
        terminalAssets.emulator(),
        terminalAssets.load(),
        this.loadFiles()(),
      ]);
      const bundle = await this.downloadBundle(files);
      if (!current() || this.ended()) return;
      this.state.set("booting");
      const banner = bundle ? SERVER_BANNER : BANNER;
      const session = await LinuxSession.boot(Emulator, buffers, files, bundle, banner, (byte) => this.write(byte));
      if (!current() || this.ended()) {
        await session.destroy();
        return;
      }
      this.session = session;
      this.bootSeconds.set((performance.now() - started) / 1000);
      this.state.set("ready");
      this.terminal?.focus();
    } catch (error) {
      if (!current()) return;
      this.error.set(message(error));
      this.state.set("failed");
    }
  }

  /** The room's server package, when it has one (the browser revalidates it by ETag on a restart). */
  private async downloadBundle(files: TerminalStartFiles): Promise<TerminalBundle | null> {
    if (!files.bundle) return null;
    const load = this.loadBundle();
    if (!load) throw new Error("This terminal cannot load the room's server package.");
    return { info: files.bundle, archive: await load() };
  }

  /** Time is up: input stops, the VM is frozen, the screen keeps its text (it can still be selected). */
  private async end(): Promise<void> {
    if (this.state() === "ended") return;
    this.generation++;
    const wasRunning = this.state() === "ready";
    this.state.set("ended");
    if (this.terminal) {
      this.terminal.options.disableStdin = true;
      if (wasRunning) this.terminal.write(TIME_UP);
    }
    await this.session?.stop();
  }

  private createTerminal(): Terminal {
    const terminal = new Terminal({
      cols: TERMINAL_COLS,
      rows: TERMINAL_ROWS,
      cursorBlink: true,
      fontFamily: '"Cascadia Mono", Consolas, "DejaVu Sans Mono", "Liberation Mono", monospace',
      fontSize: 14,
      scrollback: 2000,
      theme: { background: "#15171c", foreground: "#e9e7e2", cursor: "#f6c445", selectionBackground: "#3b5fa8" },
    });
    terminal.open(this.screen().nativeElement);
    terminal.onData((data) => {
      if (this.state() === "ready") this.session?.send(data);
    });
    return terminal;
  }

  /** Serial output arrives one byte at a time; it is written to the screen in one batch per task. */
  private write(byte: number): void {
    this.pending.push(byte);
    if (this.flushQueued) return;
    this.flushQueued = true;
    queueMicrotask(() => {
      this.flushQueued = false;
      const bytes = Uint8Array.from(this.pending);
      this.pending = [];
      this.terminal?.write(bytes);
    });
  }
}
