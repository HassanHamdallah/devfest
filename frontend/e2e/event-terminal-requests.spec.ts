import { test, expect, chromium, Page } from "@playwright/test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  EventAdminShortcuts,
  EventApiSession,
  PRACTICE_KEYS,
  RehearsalAccounts,
  readRehearsalAccounts,
  resetRehearsal,
  setPracticeKeys,
} from "./event-api-helper";
import { signIn } from "./event-ui-helper";
import { buildLinuxRoomFixture } from "./linux-room-fixture";
import { ask, terminal } from "./linux-terminal-helper";

/**
 * Server load of one Linux-room team (LINUX E): what the browser asks the server while the terminal runs, on a restart
 * and on a page refresh. The cache assertions use a persistent browser profile, because a throw-away test context keeps
 * its HTTP cache in a few megabytes of memory and drops the 7 MB Linux image, which a real browser keeps on disk.
 */
let accounts: RehearsalAccounts;
const WIDE = { width: 1440, height: 900 };
const BOOT = { timeout: 120000 };
const LINUX_TEAM = 8;
/** A revalidation answered 304 (headers only) or a cache hit; a real download is far bigger. */
const NO_BODY = 2000;

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

interface Seen {
  phase: string;
  method: string;
  url: string;
  bytes: number;
}

/** Records every request of a page (also the ones the browser answers from its cache) with the current phase. */
async function record(page: Page, seen: Seen[], phase: { current: string }) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Network.enable");
  const byId = new Map<string, Seen>();
  cdp.on("Network.requestWillBeSent", (event) => {
    if (event.request.url.startsWith("data:") || event.request.url.startsWith("blob:")) return;
    const entry = { phase: phase.current, method: event.request.method, url: event.request.url, bytes: 0 };
    byId.set(event.requestId, entry);
    seen.push(entry);
  });
  cdp.on("Network.loadingFinished", (event) => {
    const entry = byId.get(event.requestId);
    if (entry) entry.bytes = event.encodedDataLength;
  });
}

const inPhase = (seen: Seen[], phase: string) => seen.filter((entry) => entry.phase === phase);
const pathOf = (entry: Seen) => new URL(entry.url).pathname;
const isTerminalAsset = (entry: Seen) => pathOf(entry).startsWith("/terminal/");
const isBundle = (entry: Seen) => pathOf(entry).endsWith("/terminal-bundle");

test("terminal requests: the nginx site caches the versioned terminal files for a year, and the page versions every one of them", () => {
  const site = fs.readFileSync(path.resolve("..", "infrastructure", "nginx.conf.example"), "utf-8");
  const block = /location \/terminal\/ \{([^}]*)\}/.exec(site)?.[1] ?? "";
  expect(block).toContain('Cache-Control "public, max-age=31536000, immutable"');
  // The binaries are stored gzipped and inflated by the page: nginx must not compress or decode them again.
  const gzipTypes = /gzip_types ([^;]*);/.exec(site)?.[1] ?? "";
  expect(gzipTypes).not.toMatch(/octet-stream|gzip|zip/);
  expect(site).not.toMatch(/gzip_static|gunzip/);
  // Immutable is only safe because every URL carries the version that changes with the content.
  const assets = fs.readFileSync(path.resolve("src", "app", "features", "event", "terminal", "terminal-assets.ts"), "utf-8");
  expect(assets).toContain("?v=${ASSET_VERSION}");
  expect(assets).toContain("?v=${TOOLS_VERSION}");
});

test("terminal requests: nothing while the team types, one request per Save and Submit, the cache for Restart and refresh", async () => {
  test.setTimeout(900000);
  resetRehearsal();
  const fixture = buildLinuxRoomFixture();
  const baseURL = test.info().project.use.baseURL;
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), "devfest-requests-"));
  const context = await chromium.launchPersistentContext(profile, {
    channel: "msedge",
    headless: true,
    viewport: WIDE,
    baseURL,
  });
  const page = context.pages()[0] ?? (await context.newPage());
  page.on("dialog", (dialog) => void dialog.accept());
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  const managerApi = await EventApiSession.signIn(accounts.managers.LINUX, accounts.seedPassword);
  const seen: Seen[] = [];
  const phase = { current: "setup" };
  try {
    await setPracticeKeys(accounts, ["LINUX"]);
    await control.setRoomActivity("AI", null);
    await control.setRoomActivity("LINUX", "LINUX_TERMINAL");
    const room = await control.roomId("LINUX");
    await managerApi.upload(`/event/rooms/${room}/terminal-bundle`, "rami-server.zip", fixture.zip);
    await control.setRound(1);
    await signIn(page, accounts.members[LINUX_TEAM], accounts.seedPassword);
    await control.startTimer();
    await control.openRoom("LINUX");
    await page.getByRole("button", { name: "Start terminal", exact: true }).click();
    await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
    await ask(page, "echo up=$((1+1))", "up");
    await record(page, seen, phase);

    phase.current = "typing";
    const started = Date.now();
    let n = 0;
    while (Date.now() - started < 40_000) {
      n++;
      await terminal(page).locator(".xterm").click();
      await page.keyboard.type(`echo tick${n} > ~/t.txt; ls ~ | wc -l`, { delay: 30 });
      await page.keyboard.press("Enter");
      await page.getByRole("textbox", { name: "PATH", exact: true }).fill(`/srv/rami/${n}`);
      await page.getByRole("textbox", { name: "USERNAME", exact: true }).fill(`user${n}`);
      await page.waitForTimeout(2000);
    }

    phase.current = "save";
    await page.getByRole("textbox", { name: "PATH", exact: true }).fill(fixture.target.path);
    await page.getByRole("textbox", { name: "USERNAME", exact: true }).fill(fixture.target.user);
    await page.getByRole("textbox", { name: "FILE_SIZE", exact: true }).fill(fixture.target.size);
    await page.getByRole("textbox", { name: "PATTERN", exact: true }).fill(fixture.target.pattern);
    await page.getByRole("textbox", { name: "Final clue", exact: true }).fill(PRACTICE_KEYS.LINUX.toLowerCase());
    await page.waitForTimeout(1500);
    phase.current = "save";
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(page.locator("event-terminal-form ui-status-chip")).toHaveText("Saved");
    await page.waitForTimeout(2000);

    phase.current = "submit";
    await page.getByRole("button", { name: "Final submit", exact: true }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Final submit", exact: true }).click();
    await expect(page.locator("event-terminal-form ui-status-chip")).toHaveText("Submitted");
    await page.waitForTimeout(2000);

    phase.current = "restart";
    await page.getByRole("button", { name: "Restart terminal", exact: true }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Confirm", exact: true }).click();
    await expect(terminal(page)).not.toHaveAttribute("data-state", "ready");
    await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
    await ask(page, "echo again=$((2+2))", "again");

    phase.current = "refresh without a saved terminal";
    await page.reload();
    await page.getByRole("button", { name: "Start terminal", exact: true }).click();
    await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
    await ask(page, "echo third=$((3+3))", "third");
    await expect(terminal(page)).not.toHaveAttribute("data-saves", "0", { timeout: 40000 });

    phase.current = "refresh with a saved terminal";
    await page.reload();
    await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
    await ask(page, "echo fourth=$((4+4))", "fourth");
    await page.waitForTimeout(2000);
    phase.current = "done";
  } finally {
    await adminApi.dispose();
    await managerApi.dispose();
    await context.close().catch(() => {});
    fs.rmSync(profile, { recursive: true, force: true });
  }

  await test.step("typing in the terminal and in the form sends nothing of its own", () => {
    const typing = inPhase(seen, "typing");
    expect(typing.length).toBeGreaterThan(0);
    // Only the page-wide safety poll of the live revision may appear (one small GET every 30 seconds).
    expect(typing.filter((entry) => !pathOf(entry).endsWith("/event/revision")).map((entry) => `${entry.method} ${pathOf(entry)}`)).toEqual([]);
  });

  await test.step("Save sends one request and Submit one request (plus the screen's own reload afterwards)", () => {
    const saves = inPhase(seen, "save").filter((entry) => entry.method === "POST");
    expect(saves.map(pathOf)).toHaveLength(1);
    expect(pathOf(saves[0])).toMatch(/\/terminal-form\/save$/);
    const submits = inPhase(seen, "submit").filter((entry) => entry.method === "POST");
    expect(submits.map(pathOf)).toHaveLength(1);
    expect(pathOf(submits[0])).toMatch(/\/terminal-form\/submit$/);
  });

  await test.step("Restart downloads no terminal file again and revalidates the package without its body", () => {
    const restart = inPhase(seen, "restart");
    expect(restart.filter(isTerminalAsset)).toEqual([]);
    const bundles = restart.filter(isBundle);
    expect(bundles).toHaveLength(1);
    expect(bundles[0].bytes).toBeLessThan(NO_BODY);
  });

  await test.step("a refresh takes every terminal file and the package from the cache", () => {
    const refresh = inPhase(seen, "refresh without a saved terminal");
    expect(refresh.filter(isTerminalAsset).length).toBeGreaterThan(0);
    for (const entry of refresh.filter((e) => isTerminalAsset(e) || isBundle(e))) {
      expect(entry.bytes, `${pathOf(entry)} was downloaded again`).toBeLessThan(NO_BODY);
    }
  });

  await test.step("a refresh with a saved terminal does not ask for the package or the tools at all", () => {
    const refresh = inPhase(seen, "refresh with a saved terminal");
    expect(refresh.filter(isBundle)).toEqual([]);
    expect(refresh.filter((entry) => pathOf(entry).endsWith("devfest-tools.tgz"))).toEqual([]);
    for (const entry of refresh.filter(isTerminalAsset)) {
      expect(entry.bytes, `${pathOf(entry)} was downloaded again`).toBeLessThan(NO_BODY);
    }
  });
});
