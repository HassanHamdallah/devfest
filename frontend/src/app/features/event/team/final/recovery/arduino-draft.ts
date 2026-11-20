/**
 * The team's unsent edits of the Arduino program, kept in sessionStorage (per signed-in team) so a refresh keeps them.
 * The server never sees a draft; it only gets the lines of a run. Reading and writing never throw: storage can be full,
 * blocked or empty, and the screen then simply starts from the original program.
 */
export interface ArduinoDraft {
  lines: string[];
  /** The letter of the chosen missing-behaviour option, or null while none is chosen. */
  option: string | null;
}

/** The longest line the server accepts. */
export const MAX_LINE_LENGTH = 300;

const storageKey = (userId: number) => `devfest.event.recovery.arduino.${userId}`;

/**
 * A stored draft when it still fits this program (the same number of lines, each a single line the server accepts, and an
 * option that is one of this team's letters); otherwise null. Pure.
 */
export function parseDraft(raw: string | null, lineCount: number, letters: readonly string[]): ArduinoDraft | null {
  if (raw === null) return null;
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof value !== "object" || value === null) return null;
  const { lines, option } = value as { lines?: unknown; option?: unknown };
  if (!Array.isArray(lines) || lines.length !== lineCount) return null;
  if (!lines.every((line) => typeof line === "string" && line.length <= MAX_LINE_LENGTH && !/[\r\n]/.test(line))) return null;
  if (option !== null && (typeof option !== "string" || !letters.includes(option))) return null;
  return { lines: lines as string[], option: option as string | null };
}

export function readDraft(userId: number | null, lineCount: number, letters: readonly string[]): ArduinoDraft | null {
  if (userId === null) return null;
  try {
    return parseDraft(sessionStorage.getItem(storageKey(userId)), lineCount, letters);
  } catch {
    return null;
  }
}

export function writeDraft(userId: number | null, draft: ArduinoDraft): void {
  if (userId === null) return;
  try {
    sessionStorage.setItem(storageKey(userId), JSON.stringify(draft));
  } catch {
    // Storage can be full or blocked; the edits just cannot survive a refresh.
  }
}

export function clearDraft(userId: number | null): void {
  if (userId === null) return;
  try {
    sessionStorage.removeItem(storageKey(userId));
  } catch {
    // Nothing to clear.
  }
}

/** True when every line is as the server sent it (nothing worth keeping a draft for). */
export function isOriginal(lines: readonly string[], original: readonly string[]): boolean {
  return lines.length === original.length && lines.every((line, i) => line === original[i]);
}
