import { computed, signal } from "@angular/core";
import type { V86, V86Options } from "v86";
import { TOOLS_VERSION } from "./tools-version";

/**
 * The self-hosted emulator files in public/terminal (versions and hashes in ATTRIBUTION.md). They contain no
 * event data, so any signed-in page may fetch them. Change ASSET_VERSION whenever a file changes: the files are
 * served with a one-year cache lifetime and the version query is what makes browsers fetch the new copy.
 *
 * The binaries are stored gzipped (tools/gzip-terminal-assets.mjs) and inflated here, so they travel compressed
 * from any static server, including the Angular dev server, which compresses nothing.
 */
const ASSET_VERSION = "v86-0.5.471-linux4";

interface AssetFile {
  path: string;
  /** Bytes on the wire, for the progress bar. */
  bytes: number;
}

const FILES = {
  wasm: { path: "terminal/v86.wasm.gz", bytes: 381_728 },
  bios: { path: "terminal/seabios.bin.gz", bytes: 67_327 },
  vgaBios: { path: "terminal/vgabios.bin.gz", bytes: 16_345 },
  image: { path: "terminal/linux4.iso.gz", bytes: 7_179_525 },
} satisfies Record<string, AssetFile>;
/** A classic script (the CSP allows only same-origin script URLs); compressed by the server where it can. */
const LIBRARY: AssetFile = { path: "terminal/libv86.js", bytes: 359_839 };

/**
 * The extra command line tools (file, base64, bzip2, zip, GNU find) installed in rooms that have a server package,
 * built by infrastructure/build-terminal-tools.py. A plain gzip file that is handed to the guest as it is (".tgz",
 * so that no server or browser inflates it on the way); like the other assets it holds no event data.
 */
const TOOLS_PATH = "terminal/devfest-tools.tgz";
const TOOLS_BYTES_LIMIT = 6 * 1024 * 1024;

export type TerminalBuffers = Record<keyof typeof FILES, ArrayBuffer>;
export type V86Constructor = new (options: V86Options) => V86;

const assetUrl = (file: AssetFile) => new URL(`${file.path}?v=${ASSET_VERSION}`, document.baseURI).href;

/**
 * Downloads the emulator and Linux image once per page visit (also in the background while a round is ASSIGNED)
 * and keeps the bytes in memory, so a restart never downloads again. `release()` drops them when the page goes.
 */
class TerminalAssetStore {
  readonly totalBytes = Object.values(FILES).reduce((sum, file) => sum + file.bytes, 0) + LIBRARY.bytes;
  private readonly received = signal<Record<string, number>>({});
  readonly loadedBytes = computed(() => Object.values(this.received()).reduce((sum, n) => sum + n, 0));
  private pending: Promise<TerminalBuffers> | null = null;
  private library: Promise<V86Constructor> | null = null;
  private toolsPending: Promise<ArrayBuffer> | null = null;

  /** Starts (or joins) the download; a failed download can be started again. */
  load(): Promise<TerminalBuffers> {
    this.pending ??= this.download().catch((error: unknown) => {
      this.pending = null;
      throw error;
    });
    return this.pending;
  }

  /** The tools archive (gzip, about 2 MB), downloaded once per page visit and kept for restarts; a failure can be retried. */
  tools(): Promise<ArrayBuffer> {
    this.toolsPending ??= this.downloadTools().catch((error: unknown) => {
      this.toolsPending = null;
      throw error;
    });
    return this.toolsPending;
  }

  /** Forgets the downloaded tools (they could not be installed), so the next start downloads them again. */
  dropTools(): void {
    this.toolsPending = null;
  }

  /** libv86.js defines the global V86 constructor; loaded by a script tag (it is not an ES module). */
  emulator(): Promise<V86Constructor> {
    this.library ??= new Promise<V86Constructor>((resolve, reject) => {
      const existing = (window as unknown as { V86?: V86Constructor }).V86;
      if (existing) return resolve(existing);
      const script = document.createElement("script");
      script.src = assetUrl(LIBRARY);
      script.async = true;
      script.onload = () => {
        this.progress(LIBRARY.path, LIBRARY.bytes);
        const loaded = (window as unknown as { V86?: V86Constructor }).V86;
        if (loaded) resolve(loaded);
        else reject(new Error("The terminal emulator did not load."));
      };
      script.onerror = () => {
        this.library = null;
        script.remove();
        reject(new Error("The terminal emulator could not be downloaded."));
      };
      document.head.append(script);
    });
    return this.library;
  }

  /** Drops the downloaded bytes; the emulator library stays loaded (a script cannot be unloaded). */
  release(): void {
    this.pending = null;
    this.toolsPending = null;
    this.received.set(this.library ? { [LIBRARY.path]: LIBRARY.bytes } : {});
  }

  private async download(): Promise<TerminalBuffers> {
    const entries = await Promise.all(
      Object.entries(FILES).map(async ([name, file]) => [name, await this.fetchFile(file)] as const),
    );
    return Object.fromEntries(entries) as TerminalBuffers;
  }

  private async fetchFile(file: AssetFile): Promise<ArrayBuffer> {
    const response = await fetch(assetUrl(file), { cache: "default" });
    if (!response.ok || !response.body) throw new Error(`Could not download ${file.path} (HTTP ${response.status}).`);
    const reader = response.body.getReader();
    const chunks: Uint8Array[] = [];
    let received = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      received += value.byteLength;
      this.progress(file.path, Math.min(received, file.bytes));
    }
    this.progress(file.path, file.bytes);
    return gunzip(new Blob(chunks as BlobPart[]));
  }

  private async downloadTools(): Promise<ArrayBuffer> {
    const url = new URL(`${TOOLS_PATH}?v=${TOOLS_VERSION}`, document.baseURI).href;
    let bytes: ArrayBuffer;
    try {
      const response = await fetch(url, { cache: "default" });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      bytes = await response.arrayBuffer();
    } catch {
      throw new Error("The terminal's extra tools could not be downloaded. Check the connection and press Restart terminal.");
    }
    const head = new Uint8Array(bytes.slice(0, 2));
    if (bytes.byteLength === 0 || bytes.byteLength > TOOLS_BYTES_LIMIT || head[0] !== 0x1f || head[1] !== 0x8b) {
      throw new Error("The terminal's extra tools arrived damaged. Press Restart terminal.");
    }
    return bytes;
  }

  private progress(path: string, bytes: number): void {
    this.received.update((current) => ({ ...current, [path]: bytes }));
  }
}

/**
 * Inflates a .gz download. A server that labels .gz files "Content-Encoding: gzip" (the Angular dev server does)
 * makes the browser inflate them already, so the gzip magic bytes decide.
 */
async function gunzip(blob: Blob): Promise<ArrayBuffer> {
  const head = new Uint8Array(await blob.slice(0, 2).arrayBuffer());
  if (head[0] !== 0x1f || head[1] !== 0x8b) return blob.arrayBuffer();
  return new Response(blob.stream().pipeThrough(new DecompressionStream("gzip"))).arrayBuffer();
}

export const terminalAssets = new TerminalAssetStore();

/** Background download while the team waits for the timer (no-op if already loaded or loading). */
export function preloadTerminal(): void {
  terminalAssets.load().catch(() => {
    // A failed preload is retried when the team presses "Start terminal", which shows the error.
  });
  terminalAssets.emulator().catch(() => {});
}

export function releaseTerminal(): void {
  terminalAssets.release();
}
