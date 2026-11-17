/**
 * The team's saved terminal (rules §18): one snapshot of the Linux VM per browser, kept in IndexedDB so that a page
 * refresh restores the files, the shell history and the screen instead of booting Linux from scratch.
 *
 * Exactly one record exists, under one fixed key. It carries the identity it belongs to (team, room, round and the
 * room's package) and is overwritten by every save, so snapshots never accumulate. A record that does not match the
 * identity asked for is deleted on sight. Every operation runs one after the other, so a delete (sign-out, round
 * end, restart) can never be overtaken by a save that was already on its way.
 *
 * Plain TypeScript with a small storage backend behind it (IndexedDB in the browser, a Map in the unit tests); like
 * terminal-assets.ts it holds no event data of its own and loads no emulator code, so the shell may import it.
 */

/** The place of the final's terminal: never a room id, so a room's snapshot is never restored there or the reverse. */
export const FINAL_SNAPSHOT_PLACE = "final";

/** Which terminal a snapshot belongs to: the team's login, the room (or the final) and the round. */
export interface SnapshotScope {
  teamId: number;
  roomId: number | typeof FINAL_SNAPSHOT_PLACE;
  round: number;
}

/** A snapshot is only valid for the exact package the room had when it was saved. */
export interface SnapshotKey extends SnapshotScope {
  packageKey: string;
}

export interface SnapshotRecord extends SnapshotKey {
  savedAt: number;
  /** The VM state from the emulator's save_state(). */
  state: ArrayBuffer;
  /** The terminal's text up to the cursor, written back to the screen after a restore. */
  screen: string;
}

/** The storage under the service: one record, read, replaced or removed. */
export interface SnapshotBackend {
  read(): Promise<SnapshotRecord | null>;
  write(record: SnapshotRecord): Promise<void>;
  remove(): Promise<void>;
}

/** The two StorageManager calls the service uses, replaceable in tests. */
export interface SnapshotStorage {
  persist?(): Promise<boolean>;
  estimate?(): Promise<{ quota?: number; usage?: number }>;
}

export const SNAPSHOT_QUOTA_ERROR = "There is not enough free browser storage for the terminal's saved state.";

const DATABASE = "devfest-terminal";
const STORE = "snapshot";
const KEY = "current";

export class TerminalSnapshots {
  private queue: Promise<unknown> = Promise.resolve();
  /** Bumped by every delete: a save requested before it is skipped. */
  private epoch = 0;
  private prepared = false;
  private storedBytes = 0;
  /** The record the last peek read, kept for the load that follows at once (a snapshot is up to ~80 MB). */
  private warm: SnapshotRecord | null = null;

  constructor(
    private readonly backend: SnapshotBackend,
    private readonly storage: SnapshotStorage | null = null,
  ) {}

  /** Whether a snapshot of this team, room and round exists (the package is checked when it is loaded). */
  peek(scope: SnapshotScope): Promise<boolean> {
    return this.enqueue(async () => {
      this.warm = null;
      const record = await this.readQuietly();
      if (!record) return false;
      if (sameScope(record, scope)) {
        this.warm = record;
        return true;
      }
      await this.removeQuietly();
      return false;
    });
  }

  /** The snapshot for exactly this key, or null; a record for anything else is deleted. */
  load(key: SnapshotKey): Promise<SnapshotRecord | null> {
    return this.enqueue(async () => {
      const record = this.warm ?? (await this.readQuietly());
      this.warm = null;
      if (!record) return null;
      if (sameScope(record, key) && record.packageKey === key.packageKey) return record;
      await this.removeQuietly();
      return null;
    });
  }

  /**
   * Replaces the stored snapshot. Rejects when the browser cannot store it (no space, a private window, IndexedDB
   * blocked): the caller then stops saving. A save that a later delete overtook is dropped without an error.
   */
  save(key: SnapshotKey, state: ArrayBuffer, screen: string): Promise<void> {
    const requested = this.epoch;
    return this.enqueue(async () => {
      if (requested !== this.epoch) return;
      this.warm = null;
      await this.prepare(state.byteLength);
      await this.backend.write({ ...key, savedAt: Date.now(), state, screen });
      this.storedBytes = state.byteLength;
    });
  }

  /** Deletes the snapshot (Restart terminal, round end, sign-out); never throws. */
  clear(): Promise<void> {
    this.epoch++;
    return this.enqueue(async () => {
      this.warm = null;
      await this.removeQuietly();
    });
  }

  /**
   * Deletes the snapshot only when it belongs to a room terminal ("room") or to the final's terminal ("final"), so
   * the room page and the final page never delete each other's saved terminal; never throws.
   */
  clearPlace(place: "room" | "final"): Promise<void> {
    this.epoch++;
    return this.enqueue(async () => {
      this.warm = null;
      const record = await this.readQuietly();
      if (!record) return;
      const isFinal = record.roomId === FINAL_SNAPSHOT_PLACE;
      if (isFinal === (place === "final")) await this.removeQuietly();
    });
  }

  /** Before the first save: asks the browser to keep the data (a refusal is fine) and checks the free space. */
  private async prepare(bytes: number): Promise<void> {
    if (this.prepared) return;
    this.prepared = true;
    const storage = this.storage;
    if (!storage) return;
    try {
      await storage.persist?.();
    } catch {
      // A refusal or an error only means the browser may clear the data under storage pressure.
    }
    let estimate: { quota?: number; usage?: number } | undefined;
    try {
      estimate = await storage.estimate?.();
    } catch {
      return;
    }
    if (estimate?.quota === undefined || estimate.usage === undefined) return;
    // The record that is about to be replaced does not need extra room.
    if (estimate.quota - estimate.usage + this.storedBytes < bytes) throw new Error(SNAPSHOT_QUOTA_ERROR);
  }

  private async readQuietly(): Promise<SnapshotRecord | null> {
    try {
      const record = await this.backend.read();
      if (record) this.storedBytes = record.state.byteLength;
      return record;
    } catch {
      return null;
    }
  }

  private async removeQuietly(): Promise<void> {
    try {
      await this.backend.remove();
      this.storedBytes = 0;
    } catch {
      // Nothing more can be done when the browser refuses; the record is checked again on the next read.
    }
  }

  private enqueue<T>(work: () => Promise<T>): Promise<T> {
    const result = this.queue.then(work, work);
    this.queue = result.catch(() => undefined);
    return result;
  }
}

function sameScope(a: SnapshotScope, b: SnapshotScope): boolean {
  return a.teamId === b.teamId && a.roomId === b.roomId && a.round === b.round;
}

/**
 * Identifies the room's package: the SHA-256 of the archive plus the terminal clock for a server package, or a hash of
 * the text files for a room without one. Only used to tell "the same content" from "changed", never as a secret.
 */
export function packageKey(files: { files: { path: string; content: string }[]; bundle: { sha256: string; clock: string | null } | null }): string {
  if (files.bundle) return `bundle:${files.bundle.sha256}:${files.bundle.clock ?? ""}`;
  return `files:${hash(JSON.stringify(files.files.map((file) => [file.path, file.content])))}`;
}

/** cyrb53: a small, fast, well-spread 53-bit string hash. */
function hash(text: string): string {
  let h1 = 0xdeadbeef;
  let h2 = 0x41c6ce57;
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    h1 = Math.imul(h1 ^ code, 2654435761);
    h2 = Math.imul(h2 ^ code, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return (4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(36);
}

/** The IndexedDB backend: a database with one store and one key, opened for every operation and closed again. */
export class IndexedDbSnapshotBackend implements SnapshotBackend {
  read(): Promise<SnapshotRecord | null> {
    return this.run<SnapshotRecord | null>("readonly", (store) => store.get(KEY), (result) => (result as SnapshotRecord | undefined) ?? null);
  }

  write(record: SnapshotRecord): Promise<void> {
    return this.run("readwrite", (store) => store.put(record, KEY), () => undefined);
  }

  remove(): Promise<void> {
    return this.run("readwrite", (store) => store.delete(KEY), () => undefined);
  }

  private async run<T>(mode: IDBTransactionMode, request: (store: IDBObjectStore) => IDBRequest, map: (result: unknown) => T): Promise<T> {
    const database = await open();
    try {
      return await new Promise<T>((resolve, reject) => {
        const transaction = database.transaction(STORE, mode);
        const issued = request(transaction.objectStore(STORE));
        // A write is only finished when the transaction commits.
        transaction.oncomplete = () => resolve(map(issued.result));
        transaction.onerror = () => reject(transaction.error ?? new Error("The browser could not store the terminal."));
        transaction.onabort = () => reject(transaction.error ?? new Error("The browser could not store the terminal."));
      });
    } finally {
      database.close();
    }
  }
}

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === "undefined") return reject(new Error("IndexedDB is not available."));
    const request = indexedDB.open(DATABASE, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("IndexedDB could not be opened."));
    request.onblocked = () => reject(new Error("IndexedDB is blocked."));
  });
}

/** The service used by the terminal, the team workspace and the sign-out. */
export const terminalSnapshots = new TerminalSnapshots(
  new IndexedDbSnapshotBackend(),
  typeof navigator !== "undefined" ? navigator.storage : null,
);
