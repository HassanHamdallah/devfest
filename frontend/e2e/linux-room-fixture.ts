import zlib from "node:zlib";

/**
 * Builds the ZIP packages the Linux room specs upload (no dependencies: a minimal ZIP writer on node:zlib).
 * Everything is derived from a fixed seed, so the same package and the same answers come out on every run.
 */

export interface PackageFile {
  /** Absolute from "/" but written without the leading slash, like in a real package. */
  path: string;
  data: Buffer;
}

/** Writes a ZIP (UTF-8 names, deflate unless the data does not shrink, one fixed DOS time). */
export function buildZip(files: PackageFile[]): Buffer {
  const dosTime = (9 << 11) | (30 << 5);
  const dosDate = ((2026 - 1980) << 9) | (3 << 5) | 14;
  const parts: Buffer[] = [];
  const central: Buffer[] = [];
  let offset = 0;
  for (const file of files) {
    const name = Buffer.from(file.path, "utf8");
    const deflated = zlib.deflateRawSync(file.data);
    const stored = deflated.length >= file.data.length;
    const body = stored ? file.data : deflated;
    const crc = zlib.crc32(file.data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(stored ? 0 : 8, 8);
    local.writeUInt16LE(dosTime, 10);
    local.writeUInt16LE(dosDate, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(file.data.length, 22);
    local.writeUInt16LE(name.length, 26);
    parts.push(local, name, body);
    const entry = Buffer.alloc(46);
    entry.writeUInt32LE(0x02014b50, 0);
    entry.writeUInt16LE(20, 4);
    entry.writeUInt16LE(20, 6);
    entry.writeUInt16LE(0x0800, 8);
    entry.writeUInt16LE(stored ? 0 : 8, 10);
    entry.writeUInt16LE(dosTime, 12);
    entry.writeUInt16LE(dosDate, 14);
    entry.writeUInt32LE(crc, 16);
    entry.writeUInt32LE(body.length, 20);
    entry.writeUInt32LE(file.data.length, 24);
    entry.writeUInt16LE(name.length, 28);
    entry.writeUInt32LE(offset, 42);
    central.push(entry, name);
    offset += local.length + name.length + body.length;
  }
  const directory = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...parts, directory, end]);
}

/** A small deterministic generator (mulberry32). */
function random(seed: number) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export interface LinuxRoomFixture {
  zip: Buffer;
  /** Installed regular files (the manifest is not installed). */
  fileCount: number;
  /** The four search parameters; together they match exactly one file, each alone matches many. */
  target: { path: string; user: string; size: string; pattern: string; file: string };
  message: string;
  clue: string;
  binary: { path: string; size: number };
  owners: string[];
  manifestRows: number;
  /** Installed regular files whose path starts with the prefix (no leading slash), e.g. "srv/". */
  fileCountUnder(prefix: string): number;
}

const DIRECTORIES = [
  "srv/rami/uploads",
  "srv/rami/logs",
  "srv/rami/cache",
  "srv/shared/exports",
  "home/guard2/inbox",
  "var/backups",
];
const USERS = ["rami", "tech9", "guard2", "root"];
const SIZES = [1337, 512, 2048, 4096, 777, 1024];
const EXTENSIONS = ["bak", "log", "cfg", "tmp", "csv"];

/**
 * The challenge room: 300 text files in six folders owned by three people (and root), plus one binary file.
 * PATH /srv/rami/uploads, USERNAME tech9, FILE_SIZE 1337c, PATTERN "*.bak": each matches several files, and
 * `find /srv/rami/uploads -user tech9 -size 1337c -name "*.bak"` finds exactly one.
 */
export function buildLinuxRoomFixture(): LinuxRoomFixture {
  const next = random(20260314);
  const pick = <T>(values: readonly T[]) => values[Math.floor(next() * values.length)];
  const target = {
    path: "/srv/rami/uploads",
    user: "tech9",
    size: "1337c",
    pattern: "*.bak",
    file: "srv/rami/uploads/handover-0427.bak",
  };
  const message = "Encrypted message: KHOOR ZRUOG, WKH ZHVW WRZHU";
  const clue = "WEST TOWER";
  const entries = new Map<string, { data: Buffer; owner: string }>();
  const filler = (size: number, label: string) => {
    const line = `${label} filler line ${"x".repeat(40)}\n`;
    return Buffer.from(line.repeat(Math.ceil(size / line.length)).slice(0, size));
  };

  const body = `${message}\nFinal clue: ${clue}\n`;
  entries.set(target.file, {
    data: Buffer.concat([Buffer.from(body), filler(1337 - body.length, "target")]),
    owner: target.user,
  });

  let index = 0;
  while (entries.size < 300) {
    index++;
    const directory = pick(DIRECTORIES);
    const owner = pick(USERS);
    const size = pick(SIZES);
    const extension = pick(EXTENSIONS);
    const path = `${directory}/${["report", "audit", "notes", "dump", "sync"][index % 5]}-${String(index).padStart(3, "0")}.${extension}`;
    const matchesAll =
      directory === "srv/rami/uploads" && owner === target.user && size === 1337 && extension === "bak";
    if (matchesAll || entries.has(path)) continue;
    entries.set(path, { data: filler(size, `decoy ${index}`), owner });
  }

  const blob = Buffer.alloc(4093);
  const fillBlob = random(7);
  for (let i = 0; i < blob.length; i++) blob[i] = Math.floor(fillBlob() * 256);
  blob[0] = 0;
  blob[1] = 0xff;
  const binary = { path: "srv/rami/data/blob.bin", size: blob.length };

  const rows = ["path,owner,group,mode"];
  for (const [path, entry] of [...entries].sort(([a], [b]) => a.localeCompare(b))) {
    if (entry.owner !== "root") rows.push(`/${path},${entry.owner},${entry.owner},0644`);
  }
  const files: PackageFile[] = [{ path: "devfest-manifest.csv", data: Buffer.from(`${rows.join("\n")}\n`) }];
  for (const [path, entry] of entries) files.push({ path, data: entry.data });
  files.push({ path: binary.path, data: blob });

  return {
    zip: buildZip(files),
    fileCount: entries.size + 1,
    target,
    message,
    clue,
    binary,
    owners: ["guard2", "rami", "tech9"],
    manifestRows: rows.length - 1,
    fileCountUnder: (prefix) => files.filter((file) => file.path.startsWith(prefix) && file.path !== "devfest-manifest.csv").length,
  };
}

/** One incompressible file of the given size (the manifest and the file together stay under 20 MB unpacked). */
export function buildHeavyPackage(unpackedBytes: number): Buffer {
  const data = Buffer.alloc(unpackedBytes);
  const next = random(99);
  for (let i = 0; i < data.length; i += 4) data.writeUInt32LE(Math.floor(next() * 4294967296), i);
  return buildZip([
    { path: "devfest-manifest.csv", data: Buffer.from("path,owner,group,mode\n/srv/heavy/payload.bin,rami,rami,0644\n") },
    { path: "srv/heavy/payload.bin", data },
  ]);
}

/** A package that breaks several rules at once, for the upload-error screenshot. */
export function buildBrokenPackage(): Buffer {
  return buildZip([
    { path: "srv/rami/ok.txt", data: Buffer.from("fine") },
    { path: "../escape.txt", data: Buffer.from("x") },
    { path: "bin/busybox", data: Buffer.from("x") },
    { path: "etc/passwd", data: Buffer.from("x") },
    {
      path: "devfest-manifest.csv",
      data: Buffer.from("srv/rami/ok.txt,Rami,rami,0644\nsrv/nothing/here.txt,rami,rami,0644\nsrv/rami/ok.txt,rami,rami,0999\n"),
    },
  ]);
}
