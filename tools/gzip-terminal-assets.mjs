// Gzips the emulator binaries in frontend/public/terminal (level 9) and prints sizes and SHA-256 for ATTRIBUTION.md.
// The browser downloads the .gz files and decompresses them itself (DecompressionStream), so they travel compressed
// from any static server. Usage: node tools/gzip-terminal-assets.mjs <dir with the original files>
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";

const sourceDir = process.argv[2];
if (!sourceDir) throw new Error("Pass the directory that holds v86.wasm, seabios.bin, vgabios.bin and linux4.iso.");
const target = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "frontend", "public", "terminal");
const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");

for (const name of ["v86.wasm", "seabios.bin", "vgabios.bin", "linux4.iso"]) {
  const raw = readFileSync(path.join(sourceDir, name));
  const gz = gzipSync(raw, { level: 9 });
  writeFileSync(path.join(target, `${name}.gz`), gz);
  console.log(`${name}\traw ${raw.length}\tsha256 ${sha256(raw)}\tgz ${gz.length}\tsha256 ${sha256(gz)}`);
}
