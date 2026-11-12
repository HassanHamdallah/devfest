import { describe, expect, it } from "vitest";
import {
  SNAPSHOT_QUOTA_ERROR,
  SnapshotBackend,
  SnapshotRecord,
  SnapshotStorage,
  TerminalSnapshots,
  packageKey,
} from "./terminal-snapshot";

/** One record in memory, like the one fixed key in IndexedDB. */
class MemoryBackend implements SnapshotBackend {
  record: SnapshotRecord | null = null;
  writes = 0;
  failWrites = false;
  failReads = false;
  async read() {
    if (this.failReads) throw new Error("blocked");
    return this.record;
  }
  async write(record: SnapshotRecord) {
    if (this.failWrites) throw new Error("QuotaExceededError");
    this.writes++;
    this.record = record;
  }
  async remove() {
    this.record = null;
  }
}

const KEY = { teamId: 7, roomId: 3, round: 1, packageKey: "bundle:abc:" };
const state = (bytes = 8) => new ArrayBuffer(bytes);

describe("TerminalSnapshots", () => {
  it("loads the snapshot saved for the same team, room, round and package", async () => {
    const backend = new MemoryBackend();
    const snapshots = new TerminalSnapshots(backend);
    await snapshots.save(KEY, state(16), "~% ls");
    const loaded = await snapshots.load(KEY);
    expect(loaded?.screen).toBe("~% ls");
    expect(loaded?.state.byteLength).toBe(16);
  });

  it("deletes and refuses a snapshot of another team, room, round or package", async () => {
    for (const other of [
      { ...KEY, teamId: 8 },
      { ...KEY, roomId: 4 },
      { ...KEY, round: 2 },
      { ...KEY, packageKey: "bundle:def:" },
    ]) {
      const backend = new MemoryBackend();
      const snapshots = new TerminalSnapshots(backend);
      await snapshots.save(KEY, state(), "x");
      expect(await snapshots.load(other)).toBeNull();
      expect(backend.record).toBeNull();
    }
  });

  it("peek answers by team, room and round only, and deletes a snapshot of another one", async () => {
    const backend = new MemoryBackend();
    const snapshots = new TerminalSnapshots(backend);
    expect(await snapshots.peek(KEY)).toBe(false);
    await snapshots.save(KEY, state(), "x");
    expect(await snapshots.peek(KEY)).toBe(true);
    expect(await snapshots.peek({ teamId: 7, roomId: 3, round: 2 })).toBe(false);
    expect(backend.record).toBeNull();
  });

  it("a load right after a peek uses the record the peek read", async () => {
    const backend = new MemoryBackend();
    const snapshots = new TerminalSnapshots(backend);
    await snapshots.save(KEY, state(), "x");
    let reads = 0;
    const read = backend.read.bind(backend);
    backend.read = async () => {
      reads++;
      return read();
    };
    expect(await snapshots.peek(KEY)).toBe(true);
    expect((await snapshots.load(KEY))?.screen).toBe("x");
    expect(reads).toBe(1);
  });

  it("keeps exactly one snapshot: every save replaces the previous one", async () => {
    const backend = new MemoryBackend();
    const snapshots = new TerminalSnapshots(backend);
    await snapshots.save(KEY, state(), "first");
    await snapshots.save({ ...KEY, round: 2 }, state(), "second");
    expect(backend.writes).toBe(2);
    expect(backend.record?.screen).toBe("second");
    expect(await snapshots.load(KEY)).toBeNull();
  });

  it("clear deletes the snapshot and never throws", async () => {
    const backend = new MemoryBackend();
    const snapshots = new TerminalSnapshots(backend);
    await snapshots.save(KEY, state(), "x");
    await snapshots.clear();
    expect(backend.record).toBeNull();
    expect(await snapshots.load(KEY)).toBeNull();
    backend.remove = async () => {
      throw new Error("blocked");
    };
    await expect(snapshots.clear()).resolves.toBeUndefined();
  });

  it("a save that was requested before a delete is dropped", async () => {
    const backend = new MemoryBackend();
    const snapshots = new TerminalSnapshots(backend);
    const late = snapshots.save(KEY, state(), "late");
    await snapshots.clear();
    await late;
    expect(backend.record).toBeNull();
    expect(backend.writes).toBe(0);
  });

  it("a failing write rejects so the caller can stop saving", async () => {
    const backend = new MemoryBackend();
    backend.failWrites = true;
    const snapshots = new TerminalSnapshots(backend);
    await expect(snapshots.save(KEY, state(), "x")).rejects.toThrow("QuotaExceededError");
    expect(backend.record).toBeNull();
  });

  it("a storage that cannot be read behaves like an empty one", async () => {
    const backend = new MemoryBackend();
    backend.failReads = true;
    const snapshots = new TerminalSnapshots(backend);
    expect(await snapshots.peek(KEY)).toBe(false);
    expect(await snapshots.load(KEY)).toBeNull();
  });

  it("asks for persistent storage once, and a refusal does not matter", async () => {
    let persisted = 0;
    const storage: SnapshotStorage = {
      persist: async () => {
        persisted++;
        return false;
      },
      estimate: async () => ({ quota: 1_000_000, usage: 0 }),
    };
    const snapshots = new TerminalSnapshots(new MemoryBackend(), storage);
    await snapshots.save(KEY, state(100), "a");
    await snapshots.save(KEY, state(100), "b");
    expect(persisted).toBe(1);
  });

  it("refuses to save when the free space is smaller than the state", async () => {
    const storage: SnapshotStorage = { estimate: async () => ({ quota: 100, usage: 60 }) };
    const backend = new MemoryBackend();
    const snapshots = new TerminalSnapshots(backend, storage);
    await expect(snapshots.save(KEY, state(50), "x")).rejects.toThrow(SNAPSHOT_QUOTA_ERROR);
    expect(backend.record).toBeNull();
  });

  it("counts the snapshot it replaces as free space", async () => {
    const storage: SnapshotStorage = { estimate: async () => ({ quota: 100, usage: 90 }) };
    const backend = new MemoryBackend();
    backend.record = { ...KEY, savedAt: 0, state: state(60), screen: "" };
    const snapshots = new TerminalSnapshots(backend, storage);
    await snapshots.load(KEY);
    await expect(snapshots.save(KEY, state(60), "x")).resolves.toBeUndefined();
  });
});

describe("packageKey", () => {
  it("changes with the package's content and clock", () => {
    const base = { files: [], bundle: { sha256: "aa", clock: null } };
    expect(packageKey(base)).toBe(packageKey({ files: [], bundle: { sha256: "aa", clock: null } }));
    expect(packageKey(base)).not.toBe(packageKey({ files: [], bundle: { sha256: "bb", clock: null } }));
    expect(packageKey(base)).not.toBe(packageKey({ files: [], bundle: { sha256: "aa", clock: "2026-03-14T09:26:00Z" } }));
  });

  it("hashes the text files of a room without a package", () => {
    const one = { files: [{ path: "logs/a.log", content: "one" }], bundle: null };
    const same = { files: [{ path: "logs/a.log", content: "one" }], bundle: null };
    const edited = { files: [{ path: "logs/a.log", content: "two" }], bundle: null };
    expect(packageKey(one)).toBe(packageKey(same));
    expect(packageKey(one)).not.toBe(packageKey(edited));
  });
});
