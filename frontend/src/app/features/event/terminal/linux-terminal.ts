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
import { type ITheme, Terminal } from "@xterm/xterm";
import { message } from "../../../core/api";
import { TerminalStartFiles } from "../../../core/event/event-models";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";
import { UiConfirmDialog } from "../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiProgress } from "../../../shared/ui/ui-progress/ui-progress";
import { LinuxSession, TERMINAL_COLS, TERMINAL_ROWS, TOOLS_FAILED, TerminalBundle } from "./linux-session";
import { SnapshotScheduler } from "./snapshot-scheduler";
import { TerminalBuffers, V86Constructor, terminalAssets } from "./terminal-assets";
import { SnapshotScope, packageKey, terminalSnapshots } from "./terminal-snapshot";

type TerminalState = "downloading" | "booting" | "ready" | "ended" | "failed";

const BANNER = "\x1b[1mYour files are in ~/logs. There is no internet in this terminal.\x1b[0m\r\n";
const TIME_UP = "\r\n\x1b[1;31mTime is up.\x1b[0m The terminal no longer accepts input.\r\n";
const MB = 1024 * 1024;
/** The shell prompt as it ends on the screen (a plain "$ ", "# " or "% ", colour codes before it). */
const AT_PROMPT = /[#%$] $/;
/** Gives up waiting for the guest to answer the invisible size command, and shows what it printed. */
const RESIZE_TIMEOUT_MS = 2000;
/** The width of xterm's scrollbar, kept free when the terminal is fitted to the screen. */
const SCROLLBAR_PX = 14;
const MIN_COLS = 40;
const MIN_ROWS = 8;
const MAX_COLS = 400;
const MAX_ROWS = 120;

/** The final's terminal look. */
const FINAL_THEME: ITheme = { background: "#15171c", foreground: "#e9e7e2", cursor: "#f6c445", selectionBackground: "#3b5fa8" };
/** The Linux room's terminal look: Ubuntu's aubergine background and its green and blue. */
export const ROOM_THEME: ITheme = {
  background: "#300a24",
  foreground: "#eeeeec",
  cursor: "#eeeeec",
  cursorAccent: "#300a24",
  selectionBackground: "rgba(238, 238, 236, 0.32)",
  black: "#2e3436",
  red: "#cc0000",
  green: "#8ae234",
  yellow: "#fce94f",
  blue: "#729fcf",
  magenta: "#ad7fa8",
  cyan: "#34e2e2",
  white: "#d3d7cf",
  brightBlack: "#555753",
  brightRed: "#ef2929",
  brightGreen: "#8ae234",
  brightYellow: "#fce94f",
  brightBlue: "#729fcf",
  brightMagenta: "#ad7fa8",
  brightCyan: "#34e2e2",
  brightWhite: "#eeeeec",
};
/** Ctrl-E (end of line) then Ctrl-U (delete to the start of the line). */
const CLEAR_LINE = String.fromCharCode(5, 21);
const NEWLINE = String.fromCharCode(13, 10);
const NOT_SAVABLE = "Your terminal cannot be saved in this browser. A page refresh will restart it.";
const NOT_RESTORED = "Your saved terminal could not be restored, so a new one was started.";

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
  /**
   * Whose terminal this is. Set for a team's room terminal: its VM is then saved in the browser and comes back after
   * a page refresh. Null (the manager's test terminal) saves nothing.
   */
  readonly snapshotScope = input<SnapshotScope | null>(null);
  /** The line shown before the first prompt when a server package is installed (the final has its own). */
  readonly packageBanner = input("Noor's server is mounted. There is no internet in this terminal.");
  /**
   * The Linux room's look: aubergine screen and, in a room with a server package, the Ubuntu-style prompt. The final
   * turns it off and keeps its own.
   */
  readonly roomLook = input(true);
  /** Seed the kernel's random pool during setup (about 2 s), so the first OpenSSL RSA decryption does not wait. */
  readonly seedEntropy = input(false);

  protected readonly state = signal<TerminalState>("downloading");
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly bootSeconds = signal<number | null>(null);
  protected readonly confirmRestart = signal(false);
  /** The terminal is being brought back from the saved state instead of booting. */
  protected readonly restoring = signal(false);
  /** Saving stopped for this session because the browser refused it (one small notice). */
  protected readonly saveNotice = signal<string | null>(null);
  /** How many saves finished in this session (read by the browser tests). */
  protected readonly saves = signal(0);
  protected readonly downloaded = computed(() => (terminalAssets.loadedBytes() / MB).toFixed(1));
  protected readonly downloadTotal = (terminalAssets.totalBytes / MB).toFixed(1);
  protected readonly loadedBytes = terminalAssets.loadedBytes;
  protected readonly totalBytes = terminalAssets.totalBytes;
  protected readonly running = computed(() => this.state() === "ready");
  /** The terminal card fills the screen (browser Fullscreen API). */
  protected readonly fullscreen = signal(false);

  private readonly card = viewChild.required<ElementRef<HTMLElement>>("card");
  private readonly frame = viewChild.required<ElementRef<HTMLElement>>("frame");
  private readonly screen = viewChild.required<ElementRef<HTMLElement>>("screen");
  private terminal: Terminal | null = null;
  private session: LinuxSession | null = null;
  /** Increments on every (re)start so a superseded boot never takes over the screen. */
  private generation = 0;
  private pending: number[] = [];
  private flushQueued = false;
  /** The size the guest's terminal driver believes in (`stty`); the fitted screen and the guest must agree. */
  private guestSize = { cols: TERMINAL_COLS, rows: TERMINAL_ROWS };
  /** While the invisible `stty` runs, whatever the guest prints (its echo and the next prompt) is held back here. */
  private holding: { text: string; timer: ReturnType<typeof setTimeout> } | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private readonly onFullscreenChange = () => {
    this.fullscreen.set(document.fullscreenElement === this.card().nativeElement);
    requestAnimationFrame(() => {
      this.fit();
      this.terminal?.focus();
    });
  };
  private scheduler: SnapshotScheduler | null = null;
  private readonly saveWhenHidden = () => {
    if (document.visibilityState === "hidden") this.scheduler?.flush();
  };
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
    document.addEventListener("visibilitychange", this.saveWhenHidden);
    document.addEventListener("fullscreenchange", this.onFullscreenChange);
    inject(DestroyRef).onDestroy(() => {
      this.generation++;
      document.removeEventListener("fullscreenchange", this.onFullscreenChange);
      this.resizeObserver?.disconnect();
      if (this.holding) clearTimeout(this.holding.timer);
      if (document.fullscreenElement === this.card().nativeElement) void document.exitFullscreen().catch(() => {});
      this.scheduler?.stop();
      document.removeEventListener("visibilitychange", this.saveWhenHidden);
      window.removeEventListener("beforeunload", this.warnBeforeLeaving);
      void this.session?.destroy();
      this.session = null;
      this.terminal?.dispose();
      this.terminal = null;
    });
  }

  /**
   * Types `text` at the shell prompt as if the team had typed it (no Enter, so they review it and press Enter
   * themselves); whatever was half typed on the line is cleared first. False while the terminal is not running.
   */
  type(text: string): boolean {
    if (this.state() !== "ready" || !this.session) return false;
    this.session.send(CLEAR_LINE + text);
    this.scheduler?.touch();
    this.terminal?.focus();
    return true;
  }

  /**
   * Full screen on the terminal card and back. A browser that refuses (or has no Fullscreen API) changes nothing and
   * shows nothing; the keyboard stays in the terminal either way.
   */
  protected async toggleFullscreen(): Promise<void> {
    const card = this.card().nativeElement;
    try {
      if (document.fullscreenElement === card) await document.exitFullscreen();
      else await card.requestFullscreen();
    } catch {
      // Refused: the terminal stays as it is.
    }
    this.terminal?.focus();
  }

  /** The restart question is a dialog outside the card, which a full screen card would hide: leave full screen first. */
  protected async askRestart(): Promise<void> {
    if (document.fullscreenElement === this.card().nativeElement) {
      try {
        await document.exitFullscreen();
      } catch {
        // Still full screen: the dialog opens anyway.
      }
    }
    this.confirmRestart.set(true);
  }

  protected async restart(): Promise<void> {
    this.confirmRestart.set(false);
    if (this.ended()) return;
    // A restart begins from the room's files: the saved terminal is thrown away first.
    this.scheduler?.stop();
    this.scheduler = null;
    const old = this.session;
    this.session = null;
    await terminalSnapshots.clear();
    await old?.destroy();
    this.terminal?.reset();
    await this.start(false);
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

  private async start(allowRestore = true): Promise<void> {
    const generation = ++this.generation;
    const current = () => generation === this.generation;
    const started = performance.now();
    this.error.set(null);
    this.notice.set(null);
    this.restoring.set(false);
    // A new or restored VM starts at the size of every terminal; the fitted screen then tells it if it differs.
    this.release(false);
    this.guestSize = { cols: TERMINAL_COLS, rows: TERMINAL_ROWS };
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
      if (!current() || this.ended()) return;
      this.state.set("booting");
      let session = allowRestore ? await this.restoreSaved(Emulator, buffers, files, current) : null;
      if (!current() || this.ended()) {
        await session?.destroy();
        return;
      }
      if (!session) {
        this.restoring.set(false);
        const bundle = await this.downloadBundle(files);
        if (!current() || this.ended()) return;
        session = await LinuxSession.boot(Emulator, buffers, files, bundle, bundle ? `\x1b[1m${this.packageBanner()}\x1b[0m\r\n` : BANNER, (byte) => this.write(byte), {
          seedEntropy: this.seedEntropy(),
          prompt: this.roomLook(),
        });
        if (!current() || this.ended()) {
          await session.destroy();
          return;
        }
      }
      this.session = session;
      this.restoring.set(false);
      this.bootSeconds.set((performance.now() - started) / 1000);
      this.state.set("ready");
      this.terminal?.focus();
      this.startSaving(files);
      this.fit();
    } catch (error) {
      if (!current()) return;
      if (error instanceof Error && error.message === TOOLS_FAILED) terminalAssets.dropTools();
      this.restoring.set(false);
      this.error.set(message(error));
      this.state.set("failed");
    }
  }

  /**
   * The saved terminal of this team, room, round and package, brought back and drawn on the screen; null when there
   * is none (the terminal then boots). A snapshot that cannot be restored is deleted, with a short notice.
   */
  private async restoreSaved(
    Emulator: V86Constructor,
    buffers: TerminalBuffers,
    files: TerminalStartFiles,
    current: () => boolean,
  ): Promise<LinuxSession | null> {
    const scope = this.snapshotScope();
    if (!scope) return null;
    const record = await terminalSnapshots.load({ ...scope, packageKey: packageKey(files) });
    if (!record || !current()) return null;
    this.restoring.set(true);
    try {
      this.terminal?.reset();
      const session = await LinuxSession.restore(Emulator, buffers, files.bundle !== null, record.state, (byte) => this.write(byte));
      this.terminal?.write(record.screen);
      return session;
    } catch {
      this.terminal?.reset();
      await terminalSnapshots.clear();
      this.notice.set(NOT_RESTORED);
      return null;
    }
  }

  /** Starts saving this terminal: after input, never while idle. Without a scope (a test terminal) nothing is saved. */
  private startSaving(files: TerminalStartFiles): void {
    this.scheduler?.stop();
    this.scheduler = null;
    const scope = this.snapshotScope();
    const session = this.session;
    if (!scope || !session) return;
    const key = { ...scope, packageKey: packageKey(files) };
    this.saveNotice.set(null);
    this.scheduler = new SnapshotScheduler(
      async () => {
        const screen = this.screenText();
        const state = await session.saveState();
        // A restart, the end of the round or a new session may have happened while the state was gathered.
        if (this.session !== session) return;
        await terminalSnapshots.save(key, state, screen);
        this.saves.update((count) => count + 1);
      },
      () => this.saveNotice.set(NOT_SAVABLE),
    );
  }

  /** The text of the screen and its history up to the cursor, to be written back after a restore. */
  private screenText(): string {
    const buffer = this.terminal?.buffer.normal;
    if (!buffer) return "";
    const last = buffer.baseY + buffer.cursorY;
    let text = "";
    for (let row = 0; row <= last; row++) {
      const line = buffer.getLine(row);
      if (!line) continue;
      if (row === last) return text + line.translateToString(false, 0, buffer.cursorX);
      const continues = buffer.getLine(row + 1)?.isWrapped === true;
      text += line.translateToString(!continues) + (continues ? "" : NEWLINE);
    }
    return text;
  }

  /** The room's server package, when it has one (the browser revalidates it by ETag on a restart). */
  private async downloadBundle(files: TerminalStartFiles): Promise<TerminalBundle | null> {
    if (!files.bundle) return null;
    const load = this.loadBundle();
    if (!load) throw new Error("This terminal cannot load the room's server package.");
    const [archive, tools] = await Promise.all([load(), terminalAssets.tools()]);
    return { info: files.bundle, archive, tools };
  }

  /** Time is up: input stops, the VM is frozen, the screen keeps its text (it can still be selected). */
  private async end(): Promise<void> {
    if (this.state() === "ended") return;
    this.generation++;
    this.scheduler?.stop();
    this.scheduler = null;
    if (this.snapshotScope()) void terminalSnapshots.clear();
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
      theme: this.roomLook() ? ROOM_THEME : FINAL_THEME,
    });
    terminal.open(this.screen().nativeElement);
    // In full screen the terminal follows the size of its frame (a window resize, a rotated phone).
    this.resizeObserver = new ResizeObserver(() => {
      if (this.fullscreen()) requestAnimationFrame(() => this.fit());
    });
    this.resizeObserver.observe(this.frame().nativeElement);
    terminal.onData((data) => {
      if (this.state() !== "ready") return;
      this.session?.send(data);
      this.scheduler?.touch();
    });
    return terminal;
  }

  /**
   * Fits the screen to its frame in full screen (as many columns and rows as fit) and back to 80 x 24 otherwise, then
   * tells the guest. The cell size is read from xterm's own screen element.
   */
  private fit(): void {
    const terminal = this.terminal;
    if (!terminal) return;
    let cols = TERMINAL_COLS;
    let rows = TERMINAL_ROWS;
    const screen = terminal.element?.querySelector<HTMLElement>(".xterm-screen");
    if (this.fullscreen() && screen && screen.offsetWidth > 0 && screen.offsetHeight > 0) {
      const frame = this.frame().nativeElement;
      const pad = getComputedStyle(this.screen().nativeElement);
      const width = frame.clientWidth - parseFloat(pad.paddingLeft) - parseFloat(pad.paddingRight) - SCROLLBAR_PX;
      const height = frame.clientHeight - parseFloat(pad.paddingTop) - parseFloat(pad.paddingBottom);
      cols = Math.min(MAX_COLS, Math.max(MIN_COLS, Math.floor(width / (screen.offsetWidth / terminal.cols))));
      rows = Math.min(MAX_ROWS, Math.max(MIN_ROWS, Math.floor(height / (screen.offsetHeight / terminal.rows))));
    }
    if (cols !== terminal.cols || rows !== terminal.rows) terminal.resize(cols, rows);
    this.syncGuest();
  }

  /** True when the shell waits at an empty prompt (nothing typed, no full-screen program running). */
  private atPrompt(): boolean {
    const buffer = this.terminal?.buffer.active;
    if (!buffer || buffer.type !== "normal") return false;
    const line = buffer.getLine(buffer.baseY + buffer.cursorY);
    return !!line && AT_PROMPT.test(line.translateToString(false, 0, buffer.cursorX));
  }

  /**
   * Tells the guest the screen's size (`stty`), invisibly: the shell's line editor must wrap at the real width. Only
   * at an empty prompt; otherwise it is tried again after the next output. What the guest prints in answer (the echo
   * and a second identical prompt) is held back, so the screen shows nothing new.
   */
  private syncGuest(): void {
    const terminal = this.terminal;
    const session = this.session;
    if (!terminal || !session || this.state() !== "ready" || this.holding) return;
    if (terminal.cols === this.guestSize.cols && terminal.rows === this.guestSize.rows) return;
    if (!this.atPrompt()) return;
    this.guestSize = { cols: terminal.cols, rows: terminal.rows };
    this.holding = { text: "", timer: setTimeout(() => this.release(), RESIZE_TIMEOUT_MS) };
    session.send(`stty cols ${terminal.cols} rows ${terminal.rows}\n`);
  }

  /** The guest answered (the held text is dropped) or took too long (it is shown). */
  private release(show = true): void {
    const holding = this.holding;
    if (!holding) return;
    clearTimeout(holding.timer);
    this.holding = null;
    if (show && holding.text) this.terminal?.write(holding.text);
  }

  /** Serial output arrives one byte at a time; it is written to the screen in one batch per task. */
  private write(byte: number): void {
    if (this.holding) {
      this.holding.text += String.fromCharCode(byte);
      // The echo of the command, a line break, then the prompt again: the guest is done.
      if (this.holding.text.includes("\n") && AT_PROMPT.test(this.holding.text)) this.release(false);
      return;
    }
    this.pending.push(byte);
    if (this.flushQueued) return;
    this.flushQueued = true;
    queueMicrotask(() => {
      this.flushQueued = false;
      const bytes = Uint8Array.from(this.pending);
      this.pending = [];
      this.terminal?.write(bytes, () => this.syncGuest());
    });
  }
}
