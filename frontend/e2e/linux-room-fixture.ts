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

// ---- The real challenge package: up to 2000 files under /home/server ----------------------------------------------

const BZ2_SAMPLE = Buffer.from(
  "QlpoOTFBWSZTWb4GW2oAADvdgAAQQAAQAAUAMidNECAAcFDTTAAFKpGjQ9DKcE7JsTcnRNyZJknBMk7JgmCeCaJ9J7J0TImieSdkwTYmifxdyRThQkL4GW2o",
  "base64",
);
/** What the sample decompresses to (bzcat must print exactly this). */
export const BZ2_SAMPLE_TEXT = "DevFest bzip2 sample line\n".repeat(20);

/** A minimal POSIX ustar archive of the given files (short names only). */
function buildTar(files: { name: string; data: Buffer }[]): Buffer {
  const blocks: Buffer[] = [];
  for (const file of files) {
    const header = Buffer.alloc(512);
    header.write(file.name, 0, "utf8");
    header.write("0000644\0", 100);
    header.write("0000000\0", 108);
    header.write("0000000\0", 116);
    header.write(`${file.data.length.toString(8).padStart(11, "0")}\0`, 124);
    header.write("00000000000\0", 136);
    header.write("        ", 148);
    header.write("0", 156);
    header.write("ustar\0", 257);
    header.write("00", 263);
    let sum = 0;
    for (const byte of header) sum += byte;
    header.write(`${sum.toString(8).padStart(6, "0")}\0 `, 148);
    blocks.push(header, file.data, Buffer.alloc((512 - (file.data.length % 512)) % 512));
  }
  blocks.push(Buffer.alloc(1024));
  return Buffer.concat(blocks);
}

export interface ServerSample {
  /** Absolute path in the guest. */
  path: string;
  user: string;
  group: string;
  /** Octal, as find -printf %m prints it (no leading zero). */
  mode: string;
  size: number;
  /** Epoch seconds from the manifest. */
  mtime: number;
}

export interface ServerFixture {
  zip: Buffer;
  /** Installed regular files (the manifest is not installed). */
  fileCount: number;
  target: { path: string; user: string; size: string; pattern: string; file: string };
  /** Where the single-parameter searches look (the whole package). */
  roots: string;
  /** Files whose owner, group, mode, size and date the manifest sets exactly. */
  samples: ServerSample[];
  /** Folders whose date the manifest sets; extracting their files must not move it. */
  datedDirectories: { path: string; mtime: number }[];
  /** `file -b` of these must start with the given text. */
  types: { path: string; starts: string }[];
  /** The clock the room sets and the files whose age against it is known: find -mtime <days> must list exactly it. */
  clock: { value: string; ages: { days: number; path: string }[]; epoch: number };
  /** The manifest text (it must not exist in the guest). */
  manifest: string;
}

const iso = (text: string) => Math.floor(Date.parse(text) / 1000);

/**
 * 2000 files under home/server with text, binary and compressed content, three owners (and root), dated files and
 * dated folders. PATH /home/server/uploads, USERNAME tech9, FILE_SIZE 1337c, PATTERN "*.bak": each matches several
 * files, and the four together exactly one.
 */
export function buildServerFixture(): ServerFixture {
  const next = random(2000);
  const pick = <T>(values: readonly T[]) => values[Math.floor(next() * values.length)];
  const base = "home/server";
  const target = {
    path: `/${base}/uploads`,
    user: "tech9",
    size: "1337c",
    pattern: "*.bak",
    file: `${base}/uploads/handover-0427.bak`,
  };
  const clockValue = "2026-03-14T09:30:00Z";
  const ages = [
    { days: 0, path: `/${base}/dated/today.txt`, at: "2026-03-14T07:30:00Z" },
    { days: 1, path: `/${base}/dated/yesterday.txt`, at: "2026-03-13T09:00:00Z" },
    { days: 10, path: `/${base}/dated/ten-days.txt`, at: "2026-03-04T09:20:00Z" },
    { days: 30, path: `/${base}/dated/thirty-days.txt`, at: "2026-02-12T08:00:00Z" },
  ];
  type Entry = { data: Buffer; owner: string; group: string; mode: string; mtime: string };
  const entries = new Map<string, Entry>();
  const put = (path: string, data: Buffer, owner = "root", group = "root", mode = "0644", mtime = "") =>
    entries.set(path, { data, owner, group, mode, mtime });
  const text = (size: number, label: string) => {
    const line = `${label} line ${"x".repeat(40)}\n`;
    return Buffer.from(line.repeat(Math.ceil(size / line.length)).slice(0, size));
  };

  const message = "Encrypted message: KHOOR ZRUOG, WKH ZHVW WRZHU";
  const clue = "WEST TOWER";
  const body = `${message}\nFinal clue: ${clue}\n`;
  put(target.file, Buffer.concat([Buffer.from(body), text(1337 - body.length, "target")]), "tech9", "tech9", "0644", "2025-12-24T18:00:00Z");

  const sampleText = Buffer.from("Server notes: the quick brown fox jumps over the lazy dog.\nSecond line of plain text.\n");
  const blob = Buffer.alloc(4093);
  const fill = random(11);
  for (let i = 0; i < blob.length; i++) blob[i] = Math.floor(fill() * 256);
  blob[0] = 0;
  blob[1] = 0xff;
  put(`${base}/types/sample.txt`, sampleText, "rami", "ops", "0640", "2025-06-01T08:09:10Z");
  put(`${base}/types/sample.gz`, zlib.gzipSync(sampleText), "rami", "rami");
  put(`${base}/types/sample.tar.gz`, zlib.gzipSync(buildTar([{ name: "inner.txt", data: sampleText }])), "tech9", "tech9");
  put(`${base}/types/sample.zip`, buildZip([{ path: "inner.txt", data: sampleText }]), "guard2", "guard2");
  put(`${base}/types/sample.bz2`, BZ2_SAMPLE, "guard2", "guard2");
  put(`${base}/types/blob.bin`, blob, "guard2", "guard2", "0600", "2026-01-02T03:04:05Z");
  for (const age of ages) put(age.path.slice(1), text(200, age.days + " days"), "rami", "rami", "0644", age.at);
  put(`${base}/archive/2024-a.txt`, text(100, "archive a"), "rami", "rami");
  put(`${base}/archive/2024-b.txt`, text(100, "archive b"), "rami", "rami");
  put(`${base}/archive/2025/report.txt`, text(100, "report"), "rami", "rami");

  const directories = ["uploads", "logs", "cache", "exports", "inbox", "backups"].map((name) => `${base}/${name}`);
  const owners = ["rami", "tech9", "guard2", "root"];
  const sizes = [1337, 512, 2048, 4096, 777, 1024];
  const extensions = ["bak", "log", "cfg", "tmp", "csv", "gz", "txt"];
  let index = 0;
  while (entries.size < 2000) {
    index++;
    const directory = pick(directories);
    const owner = pick(owners);
    const size = pick(sizes);
    const extension = pick(extensions);
    const path = `${directory}/${["report", "audit", "notes", "dump", "sync"][index % 5]}-${String(index).padStart(4, "0")}.${extension}`;
    const matchesAll = directory === `${base}/uploads` && owner === target.user && size === 1337 && extension === "bak";
    if (matchesAll || entries.has(path)) continue;
    put(path, text(size, `file ${index}`), owner, owner);
  }

  const rows = ["path,owner,group,mode,mtime"];
  rows.push(`/${base}/archive/**,rami,rami,0644,2024-02-29T13:45:10Z`);
  rows.push(`/${base}/archive/2025/**,rami,rami,0644,2025-06-01T08:09:10Z`);
  for (const [path, entry] of [...entries].sort(([a], [b]) => a.localeCompare(b))) {
    if (path.startsWith(`${base}/archive/`)) continue;
    if (entry.owner !== "root" || entry.group !== "root" || entry.mode !== "0644" || entry.mtime) {
      rows.push(`/${path},${entry.owner},${entry.group},${entry.mode},${entry.mtime}`);
    }
  }
  const manifest = `${rows.join("\n")}\n`;
  const files: PackageFile[] = [{ path: "devfest-manifest.csv", data: Buffer.from(manifest) }];
  for (const [path, entry] of entries) files.push({ path, data: entry.data });

  const sample = (path: string): ServerSample => {
    const entry = entries.get(path)!;
    return {
      path: `/${path}`,
      user: entry.owner,
      group: entry.group,
      mode: String(parseInt(entry.mode, 8).toString(8)),
      size: entry.data.length,
      mtime: iso(entry.mtime),
    };
  };
  return {
    zip: buildZip(files),
    fileCount: entries.size,
    target,
    roots: `/${base}`,
    samples: [target.file, `${base}/types/sample.txt`, `${base}/types/blob.bin`, ...ages.map((age) => age.path.slice(1))].map(sample),
    datedDirectories: [
      { path: `/${base}/archive`, mtime: iso("2024-02-29T13:45:10Z") },
      { path: `/${base}/archive/2025`, mtime: iso("2025-06-01T08:09:10Z") },
    ],
    types: [
      { path: `/${base}/types/sample.txt`, starts: "ASCII text" },
      { path: `/${base}/types/sample.gz`, starts: "gzip compressed data" },
      { path: `/${base}/types/sample.tar.gz`, starts: "gzip compressed data" },
      { path: `/${base}/types/sample.zip`, starts: "Zip archive data" },
      { path: `/${base}/types/sample.bz2`, starts: "bzip2 compressed data" },
      { path: `/${base}/types/blob.bin`, starts: "data" },
    ],
    clock: { value: clockValue, ages: ages.map(({ days, path }) => ({ days, path })), epoch: iso(clockValue) },
    manifest,
  };
}
