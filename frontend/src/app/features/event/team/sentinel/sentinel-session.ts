/**
 * What a stage keeps in this browser tab only (sessionStorage): the crossover child being built, so a refresh keeps it,
 * and the child that was submitted, so the saved stage can show it. Both are per team, room and stage, so another
 * team signing in on the same tab never sees them. Storage can be blocked or full: every call is safe and a failure
 * only means nothing is remembered.
 */

function key(kind: "draft" | "submitted", teamId: number, roomId: number, stage: number): string {
  return `devfest.sentinel.${kind}.${teamId}.${roomId}.${stage}`;
}

function read(name: string): string | null {
  try {
    return sessionStorage.getItem(name);
  } catch {
    return null;
  }
}

function write(name: string, value: string): void {
  try {
    sessionStorage.setItem(name, value);
  } catch {
    // Nothing remembered; the stage still works.
  }
}

function remove(name: string): void {
  try {
    sessionStorage.removeItem(name);
  } catch {
    // Nothing to forget.
  }
}

export const sentinelSession = {
  draft: (teamId: number, roomId: number, stage: number) => read(key("draft", teamId, roomId, stage)),
  saveDraft: (teamId: number, roomId: number, stage: number, value: string) => write(key("draft", teamId, roomId, stage), value),
  clearDraft: (teamId: number, roomId: number, stage: number) => remove(key("draft", teamId, roomId, stage)),
  submitted: (teamId: number, roomId: number, stage: number) => read(key("submitted", teamId, roomId, stage)),
  saveSubmitted: (teamId: number, roomId: number, stage: number, value: string) => write(key("submitted", teamId, roomId, stage), value),
};
