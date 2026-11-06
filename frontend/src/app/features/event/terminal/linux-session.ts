import type { V86 } from "v86";
import type { TerminalBundleInfo, TerminalStartFiles } from "../../../core/event/event-models";
import type { TerminalBuffers, V86Constructor } from "./terminal-assets";

export const TERMINAL_COLS = 80;
export const BUNDLE_FAILED =
  "The room's server could not be set up in this terminal. Press Restart terminal, or ask an organizer.";
export const TERMINAL_ROWS = 24;

const BOOT_TIMEOUT_MS = 120_000;
/** The shell prompt at the end of the serial output ("~% " in the image, "# " or "$ " elsewhere). */
const PROMPT = /[#%$] $/;
const READY_PATTERN = /__DEVFEST_READY__\r?\n/;
const FAILED_PATTERN = /__DEVFEST_FAILED__\r?\n/;
/**
 * Typed into the shell once, invisibly: the room's files are on the 9p share (/mnt) and are copied into the home
 * directory. The quotes split the mark so the echoed command line never matches it.
 */
const SETUP =
  `stty cols ${TERMINAL_COLS} rows ${TERMINAL_ROWS} 2>/dev/null; mkdir -p ~/logs; cp -r /mnt/. ~/ 2>/dev/null; cd ~;` +
  ` echo __DEVFEST""_READY__\n`;

/** The three files a server package puts on the 9p share (created by the page, removed by the setup). */
const BUNDLE_FILE = "devfest-bundle.tgz";
const PASSWD_FILE = "devfest-passwd";
const GROUP_FILE = "devfest-group";
/**
 * Typed into the shell once, invisibly, for a room with a server package. This BusyBox tar has no -z flag, so
 * gzip unpacks into the pipe. The owners are appended to /etc/passwd and /etc/group before the shell is handed
 * over, then the setup files are removed. Any failing step ends in the failure mark instead of the ready mark.
 */
const BUNDLE_SETUP =
  `stty cols ${TERMINAL_COLS} rows ${TERMINAL_ROWS} 2>/dev/null;` +
  ` if gzip -dc /mnt/${BUNDLE_FILE} | tar -xf - -C / && cat /mnt/${PASSWD_FILE} >> /etc/passwd &&` +
  ` cat /mnt/${GROUP_FILE} >> /etc/group; then rm -f /mnt/${BUNDLE_FILE} /mnt/${PASSWD_FILE} /mnt/${GROUP_FILE};` +
  ` cd ~; echo __DEVFEST""_READY__; else echo __DEVFEST""_FAILED__; fi\n`;

/** A room's server package as the page downloaded it: the gzip bytes and the accounts to add. */
export interface TerminalBundle {
  info: TerminalBundleInfo;
  archive: ArrayBuffer;
}

/** The v86 9p filesystem object (not part of v86.d.ts); only these two methods are used. */
interface Fs9p {
  SearchPath(path: string): { id: number };
  CreateDirectory(name: string, parentId: number): number;
}

/**
 * One booted Linux VM in the browser: no network adapter, no keyboard/mouse capture, the serial port is the
 * console. Output bytes reach `onOutput` only after the setup ran, so the team never sees the boot log.
 */
export class LinuxSession {
  private forwarding = false;
  private destroyed = false;

  private constructor(
    private readonly emulator: V86,
    private readonly onOutput: (byte: number) => void,
  ) {}

  /**
   * Boots, places `files` under the home directory (or installs `bundle` from the root of the file system) and
   * resolves once the shell is ready. `banner` is shown right before the first prompt. A failing package setup
   * rejects with a clear message instead of handing over a half-prepared shell.
   */
  static async boot(
    Emulator: V86Constructor,
    buffers: TerminalBuffers,
    files: TerminalStartFiles,
    bundle: TerminalBundle | null,
    banner: string,
    onOutput: (byte: number) => void,
  ): Promise<LinuxSession> {
    const emulator = new Emulator({
      wasm_fn: async (imports) => (await WebAssembly.instantiate(buffers.wasm, imports)).instance.exports,
      bios: { buffer: buffers.bios },
      vga_bios: { buffer: buffers.vgaBios },
      cdrom: { buffer: buffers.image },
      memory_size: 64 * 1024 * 1024,
      filesystem: {},
      // "none" is neither ne2k nor virtio, so v86 creates no network card at all (its default is an NE2000).
      net_device: { type: "none" as "ne2k" },
      autostart: true,
      disable_keyboard: true,
      disable_mouse: true,
      disable_speaker: true,
    });
    const session = new LinuxSession(emulator, onOutput);
    try {
      await session.start(files, bundle, new TextEncoder().encode(banner));
    } catch (error) {
      await session.destroy();
      throw error;
    }
    return session;
  }

  send(data: string): void {
    if (!this.destroyed) this.emulator.serial0_send(data);
  }

  /** Freezes the VM (time is up); the screen keeps its content. */
  async stop(): Promise<void> {
    if (!this.destroyed) await this.emulator.stop();
  }

  async destroy(): Promise<void> {
    if (this.destroyed) return;
    this.destroyed = true;
    await this.emulator.destroy();
  }

  private async start(files: TerminalStartFiles, bundle: TerminalBundle | null, banner: Uint8Array): Promise<void> {
    let text = "";
    // `matched` runs synchronously inside the output listener, before the next byte arrives.
    let waiter: { test: (text: string) => boolean; matched: () => void } | null = null;
    this.emulator.add_listener("serial0-output-byte", (byte: number) => {
      if (this.forwarding) {
        this.onOutput(byte);
        return;
      }
      text = (text + String.fromCharCode(byte)).slice(-4096);
      if (waiter?.test(text)) {
        const done = waiter;
        waiter = null;
        done.matched();
      }
    });
    const waitFor = (test: (text: string) => boolean, onMatch = () => {}) =>
      withTimeout(
        new Promise<void>((resolve) => (waiter = { test, matched: () => (onMatch(), resolve()) })),
        BOOT_TIMEOUT_MS,
        "Linux did not start in time. Press Restart terminal.",
      );

    await waitFor((output) => PROMPT.test(output));
    text = "";
    // Everything after the ready mark, starting with the next prompt, is the team's terminal.
    let failed = false;
    const finished = waitFor(
      (output) => READY_PATTERN.test(output) || FAILED_PATTERN.test(output),
      () => {
        failed = FAILED_PATTERN.test(text);
        if (failed) return;
        this.forwarding = true;
        banner.forEach((byte) => this.onOutput(byte));
      },
    );
    if (bundle) {
      await this.placeBundle(bundle);
      this.send(BUNDLE_SETUP);
    } else {
      await this.placeFiles(files);
      this.send(SETUP);
    }
    await finished;
    if (failed) throw new Error(BUNDLE_FAILED);
  }

  /** The package and its accounts as three files on the share; the setup removes them again. */
  private async placeBundle(bundle: TerminalBundle): Promise<void> {
    const encoder = new TextEncoder();
    await this.emulator.create_file(`/${BUNDLE_FILE}`, new Uint8Array(bundle.archive));
    await this.emulator.create_file(`/${PASSWD_FILE}`, encoder.encode(bundle.info.etcPasswd));
    await this.emulator.create_file(`/${GROUP_FILE}`, encoder.encode(bundle.info.etcGroup));
  }

  private async placeFiles(files: TerminalStartFiles): Promise<void> {
    const fs = (this.emulator as unknown as { fs9p?: Fs9p }).fs9p;
    if (!fs) throw new Error("The terminal file system is not available.");
    const encoder = new TextEncoder();
    for (const file of files.files) {
      let parent = fs.SearchPath("/").id;
      let current = "";
      for (const dir of file.path.split("/").slice(0, -1)) {
        current += `/${dir}`;
        if (fs.SearchPath(current).id === -1) fs.CreateDirectory(dir, parent);
        parent = fs.SearchPath(current).id;
      }
      await this.emulator.create_file(`/${file.path}`, encoder.encode(file.content));
    }
  }
}

function withTimeout<T>(work: Promise<T>, ms: number, message: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => (timer = setTimeout(() => reject(new Error(message)), ms)));
  return Promise.race([work, timeout]).finally(() => clearTimeout(timer));
}
