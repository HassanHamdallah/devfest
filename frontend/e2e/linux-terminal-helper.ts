import { expect, Page } from "@playwright/test";
import type { LinuxRoomFixture } from "./linux-room-fixture";

/** Shared by the Linux room specs: driving the real in-browser terminal (v86 + xterm.js). */

export function terminal(page: Page) {
  return page.locator("event-linux-terminal section.linux-terminal");
}

/** Everything the terminal shows right now, as one string. */
export async function screen(page: Page): Promise<string> {
  return (await terminal(page).locator(".xterm-rows").innerText()).replace(/ /g, " ");
}

/**
 * Types a command and waits for `marker=<value>` in its output. The command echo contains "marker=$(" or
 * "marker=`", never a digit or word right after the equals sign, so only the real output matches.
 */
export async function ask(page: Page, command: string, marker: string): Promise<string> {
  await terminal(page).locator(".xterm").click();
  await page.keyboard.type(command);
  await page.keyboard.press("Enter");
  const pattern = new RegExp(`\\b${marker}=([^\\s$\`][^\\n]*)`);
  let value = "";
  await expect
    .poll(async () => (value = ((await screen(page)).match(pattern)?.[1] ?? "").trim()), { timeout: 30000 })
    .not.toBe("");
  return value;
}

/**
 * The challenge the fixture is built for: each of the four parameters alone returns several files, and the full
 * `find <PATH> -user <USERNAME> -size <FILE_SIZE> -name "<PATTERN>"` returns exactly the target file.
 */
export async function expectFindChallenge(page: Page, fixture: LinuxRoomFixture): Promise<void> {
  const { target } = fixture;
  const byPath = Number(await ask(page, `echo pcount=$(find ${target.path} -type f | wc -l)`, "pcount"));
  const byUser = Number(await ask(page, `echo ucount=$(find /srv /home /var -user ${target.user} -type f | wc -l)`, "ucount"));
  const bySize = Number(await ask(page, `echo scount=$(find /srv /home /var -type f -size ${target.size} | wc -l)`, "scount"));
  const byName = Number(
    await ask(page, `echo ncount=$(find /srv /home /var -type f -name "${target.pattern}" | wc -l)`, "ncount"),
  );
  for (const [label, count] of [
    ["PATH", byPath],
    ["USERNAME", byUser],
    ["FILE_SIZE", bySize],
    ["PATTERN", byName],
  ] as const) {
    expect(count, `${label} alone returns several files`).toBeGreaterThan(4);
  }
  const all = `find ${target.path} -user ${target.user} -size ${target.size} -name "${target.pattern}"`;
  expect(await ask(page, `echo all=$(${all} | wc -l)`, "all"), "all four together return exactly one file").toBe("1");
  expect(await ask(page, `echo found=$(${all})`, "found"), "and it is the target").toBe(`/${target.file}`);
}
