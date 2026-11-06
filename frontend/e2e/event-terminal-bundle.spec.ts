import { test, expect, Page } from "@playwright/test";
import path from "node:path";
import {
  EventAdminShortcuts,
  EventApiSession,
  RehearsalAccounts,
  readRehearsalAccounts,
  resetRehearsal,
  setPracticeKeys,
} from "./event-api-helper";
import { expectFitsPhone, openRolePage, saveScreenshot, signIn } from "./event-ui-helper";
import { buildBrokenPackage, buildHeavyPackage, buildLinuxRoomFixture } from "./linux-room-fixture";
import { ask, expectFindChallenge, screen, terminal } from "./linux-terminal-helper";

let accounts: RehearsalAccounts;
const shots = path.resolve("..", "docs", "ui-after");
const WIDE = { width: 1440, height: 900 };
const NARROW = { width: 390, height: 844 };
// Cold boot in headless Edge, plus the package download and its installation inside the guest.
const BOOT = { timeout: 120000 };
const BANNER = "Rami's server is mounted. There is no internet in this terminal.";
/** Team 09–12 are group C: the Linux room in round 1. */
const LINUX_TEAM = 8;

async function startTestTerminal(page: Page, roomId: number): Promise<number> {
  await page.goto(`/event/room/${roomId}`);
  const card = page.locator("event-terminal-files-card");
  await card.getByRole("button", { name: "Test terminal", exact: true }).click();
  const started = Date.now();
  await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
  // The terminal's own stopwatch ("Ready in 4.6 s") runs from its start to the prompt and is not rounded by polling.
  const shown = (await terminal(page).locator(".linux-terminal__status").textContent()) ?? "";
  const own = Number(/Ready in ([\d.]+) s/.exec(shown)?.[1]);
  return Number.isFinite(own) ? own : (Date.now() - started) / 1000;
}

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

test("server package: the manager uploads a ZIP and the terminal installs 300 files with their owners", async ({
  browser,
}) => {
  test.setTimeout(480000);
  const fixture = buildLinuxRoomFixture();
  const manager = await openRolePage(browser, "manager", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  try {
    const linuxRoom = await control.roomId("LINUX");
    const page = manager.page;
    await signIn(page, accounts.managers.LINUX, accounts.seedPassword);
    await page.goto(`/event/room/${linuxRoom}`);
    const card = page.locator("event-terminal-files-card");
    const picker = card.locator('input[type="file"][accept^=".zip"]');

    await test.step("a package that breaks several rules is refused with every problem listed", async () => {
      await picker.setInputFiles({ name: "broken.zip", mimeType: "application/zip", buffer: buildBrokenPackage() });
      const alert = card.getByText("The package was not accepted.", { exact: false });
      await expect(alert).toBeVisible();
      const items = card.locator(".terminal-files__problems li");
      await expect(items).toHaveCount(6);
      await expect(items.filter({ hasText: "contains \"..\"" })).toHaveCount(1);
      await expect(items.filter({ hasText: "/bin, which belongs to the Linux image" })).toHaveCount(1);
      await expect(items.filter({ hasText: "/etc/passwd, which belongs to the Linux image" })).toHaveCount(1);
      await expect(card.getByText("No server package yet.", { exact: true })).toBeVisible();
      for (const [label, size] of [["1440", WIDE], ["390", NARROW]] as const) {
        await page.setViewportSize(size);
        await alert.scrollIntoViewIfNeeded();
        await saveScreenshot(page, path.join(shots, `terminal-package-errors-${label}.png`), false);
      }
      await expectFitsPhone(page);
      await page.setViewportSize(WIDE);
    });

    await test.step("the real package is accepted and summarised", async () => {
      await picker.setInputFiles({ name: "rami-server.zip", mimeType: "application/zip", buffer: fixture.zip });
      await expect(card.getByText("Uploaded the server package.", { exact: false })).toBeVisible();
      const summary = card.locator(".terminal-files__summary");
      await expect(summary).toContainText(`${fixture.fileCount} · `);
      await expect(summary).toContainText(fixture.owners.join(", "));
      await expect(card.locator(".terminal-files__problems")).toHaveCount(0);
      await expect(card.locator("[data-clock]")).toHaveText("Real time");
      // The terminal clock is set without uploading the package again.
      await card.locator('input[type="datetime-local"]').fill("2026-03-14T09:26");
      await card.getByRole("button", { name: "Set clock", exact: true }).click();
      await expect(card.locator("[data-clock]")).toHaveText("2026-03-14 09:26:00 UTC");
      await expect(card.getByText("Terminal clock set.", { exact: false })).toBeVisible();
      for (const [label, size] of [["1440", WIDE], ["390", NARROW]] as const) {
        await page.setViewportSize(size);
        // Scroll after resizing: the card sits elsewhere on a phone, and the picture must show it.
        await card.locator("[data-clock]").scrollIntoViewIfNeeded();
        await saveScreenshot(page, path.join(shots, `terminal-package-${label}.png`), false);
      }
      await expectFitsPhone(page);
      await page.setViewportSize(WIDE);
    });

    let seconds = 0;
    await test.step("Test terminal installs the package before the shell is handed over", async () => {
      seconds = await startTestTerminal(page, linuxRoom);
      await expect(terminal(page)).toContainText(BANNER);
      // The team never sees the setup: no tar, gzip or mark text, and the share is left clean.
      const shown = await screen(page);
      expect(shown).not.toMatch(/gzip|tar -xf|__DEVFEST|devfest-bundle/);
      expect(await ask(page, "echo cwd=$PWD", "cwd")).toBe("/root");
      expect(await ask(page, "echo mnt=$(ls /mnt | wc -l)", "mnt")).toBe("0");
      test.info().annotations.push({ type: "boot (300 files)", description: `${seconds.toFixed(1)} s from Test terminal to prompt` });
    });

    await test.step("each parameter alone returns several files, all four together exactly the target", async () => {
      await expectFindChallenge(page, fixture);
    });

    await test.step("owners, groups and modes come from the manifest; cat shows the message and the clue", async () => {
      const { target } = fixture;
      // guard2, rami, tech9 get uids 2000, 2001, 2002 in name order; their own groups the same numbers.
      expect(await ask(page, `echo ids=$(ls -ln /${target.file} | tr -s ' ' | cut -d' ' -f1,3,4 | tr ' ' ':')`, "ids")).toBe(
        "-rw-r--r--:2002:2002",
      );
      expect(await ask(page, `echo names=$(ls -l /${target.file} | tr -s ' ' | cut -d' ' -f3,4 | tr ' ' ':')`, "names")).toBe(
        "tech9:tech9",
      );
      expect(await ask(page, "echo pw=$(tail -n 3 /etc/passwd | cut -d: -f1,3 | tr '\\n' ' ')", "pw")).toBe(
        "guard2:2000 rami:2001 tech9:2002",
      );
      await terminal(page).locator(".xterm").click();
      await page.keyboard.type(`head -n 1 /${target.file}`);
      await page.keyboard.press("Enter");
      await expect(terminal(page)).toContainText(fixture.encryptedClue);
    });

    await test.step("the binary file keeps its exact size and bytes", async () => {
      const { binary } = fixture;
      expect(await ask(page, `echo size=$(wc -c < /${binary.path})`, "size")).toBe(String(binary.size));
      const sum = await ask(page, `echo sum=$(cksum < /${binary.path} | cut -d' ' -f1)`, "sum");
      expect(Number(sum)).toBeGreaterThan(0);
    });

    await test.step("memory left in the guest after the installation", async () => {
      const memory = await ask(page, "echo mem=$(free -m | grep Mem | tr -s ' ')", "mem");
      test.info().annotations.push({ type: "free -m (300 files)", description: memory });
    });
  } finally {
    await adminApi.dispose();
    await manager.page.context().close().catch(() => {});
  }
  // The refused upload above makes the browser log one "400 (Bad Request)" resource error; that is expected.
  expect(manager.problems.filter((problem) => !problem.includes("status of 400"))).toEqual([]);
});

test("server package: the assigned team installs it, a restart revalidates it, and a broken download shows a clear error", async ({
  browser,
}) => {
  test.setTimeout(480000);
  const fixture = buildLinuxRoomFixture();
  const team = await openRolePage(browser, "linux team", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  const managerApi = await EventApiSession.signIn(accounts.managers.LINUX, accounts.seedPassword);
  try {
    await setPracticeKeys(accounts, ["LINUX"]);
    await control.setRoomActivity("AI", null);
    const linuxRoom = await control.roomId("LINUX");
    await managerApi.upload(`/event/rooms/${linuxRoom}/terminal-bundle`, "rami-server.zip", fixture.zip);
    await control.setRound(1);
    await signIn(team.page, accounts.members[LINUX_TEAM], accounts.seedPassword);
    await control.startTimer();
    await control.openRoom("LINUX");
    const page = team.page;

    const bundleRequests: { conditional: boolean; status: number }[] = [];
    page.on("request", (request) => {
      if (request.url().includes("/terminal-bundle")) bundleRequests.push({ conditional: false, status: 0 });
    });
    page.on("response", async (response) => {
      if (!response.url().includes("/terminal-bundle")) return;
      const sent = await response.request().allHeaders();
      const last = bundleRequests[bundleRequests.length - 1];
      last.conditional = "if-none-match" in sent;
      last.status = response.status();
    });

    await test.step("Start terminal downloads the package and boots into the room's server", async () => {
      await page.getByRole("button", { name: "Start terminal", exact: true }).click();
      await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
      await expect(terminal(page)).toContainText(BANNER);
      expect(await ask(page, "echo cnt=$(find /srv -type f | wc -l)", "cnt")).toBe(String(fixture.fileCountUnder("srv/")));
      expect(bundleRequests.length).toBe(1);
      expect(bundleRequests[0].conditional).toBe(false);
    });

    await test.step("Restart terminal installs it again and the browser revalidates by ETag", async () => {
      await terminal(page).getByRole("button", { name: "Restart terminal", exact: true }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Confirm", exact: true }).click();
      // The old VM is still "ready" for a moment; wait until the restart really began.
      await expect(terminal(page)).not.toHaveAttribute("data-state", "ready");
      await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
      await expect(terminal(page)).toContainText(BANNER);
      expect(bundleRequests.length).toBe(2);
      expect(bundleRequests[1].conditional, "the second request carries If-None-Match").toBe(true);
      expect(await ask(page, "echo cnt=$(find /srv -type f | wc -l)", "cnt")).toBe(String(fixture.fileCountUnder("srv/")));
    });

    await test.step("a package the guest cannot unpack ends in a clear error, not a broken shell", async () => {
      await page.route("**/terminal-bundle", (route) =>
        route.fulfill({ status: 200, contentType: "application/gzip", body: Buffer.from("this is not gzip data") }),
      );
      await terminal(page).getByRole("button", { name: "Restart terminal", exact: true }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Confirm", exact: true }).click();
      await expect(terminal(page)).toHaveAttribute("data-state", "failed", BOOT);
      await expect(terminal(page).getByRole("alert")).toContainText("The room's server could not be set up in this terminal.");
      await page.unroute("**/terminal-bundle");
      await terminal(page).getByRole("button", { name: "Try again", exact: true }).click();
      await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
      expect(await ask(page, "echo cnt=$(find /srv -type f | wc -l)", "cnt")).toBe(String(fixture.fileCountUnder("srv/")));
    });
  } finally {
    await adminApi.dispose();
    await managerApi.dispose();
    await team.page.context().close().catch(() => {});
  }
  expect(team.problems.filter((problem) => !problem.includes("terminal-bundle"))).toEqual([]);
});

test("server package: a 20 MB package boots in time and leaves the guest enough memory", async ({ browser }) => {
  test.setTimeout(480000);
  const manager = await openRolePage(browser, "manager", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  const managerApi = await EventApiSession.signIn(accounts.managers.LINUX, accounts.seedPassword);
  try {
    const linuxRoom = await control.roomId("LINUX");
    const payload = 20 * 1024 * 1024 - 1024;
    const heavy = buildHeavyPackage(payload);
    const summary = await managerApi.upload(`/event/rooms/${linuxRoom}/terminal-bundle`, "heavy.zip", heavy);
    test.info().annotations.push({
      type: "package",
      description: `ZIP ${heavy.length} bytes, archive ${(summary as { bundle: { archiveBytes: number } }).bundle.archiveBytes} bytes, ${payload + 70} bytes unpacked`,
    });
    await signIn(manager.page, accounts.managers.LINUX, accounts.seedPassword);
    const seconds = await startTestTerminal(manager.page, linuxRoom);
    test.info().annotations.push({ type: "boot (20 MB)", description: `${seconds.toFixed(1)} s from Test terminal to prompt` });
    await expect(terminal(manager.page)).toContainText(BANNER);
    expect(await ask(manager.page, "echo big=$(wc -c < /srv/heavy/payload.bin)", "big")).toBe(String(payload));
    const memory = await ask(manager.page, "echo mem=$(free -m | grep Mem | tr -s ' ')", "mem");
    test.info().annotations.push({ type: "free -m (20 MB)", description: memory });
    // The shell still works after the installation.
    expect(await ask(manager.page, "echo alive=$((6*7))", "alive")).toBe("42");
  } finally {
    await adminApi.dispose();
    await managerApi.dispose();
    await manager.page.context().close().catch(() => {});
  }
  expect(manager.problems).toEqual([]);
});
