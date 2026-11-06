import { test, expect, Page } from "@playwright/test";
import fs from "node:fs";
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
import { expectFitsPhone, openRolePage, saveScreenshot, signIn } from "./event-ui-helper";
import { buildLinuxRoomFixture } from "./linux-room-fixture";
import { terminal } from "./linux-terminal-helper";

let accounts: RehearsalAccounts;
const shots = path.resolve("..", "docs", "ui-after");
const WIDE = { width: 1440, height: 900 };
const NARROW = { width: 390, height: 844 };
const BOOT = { timeout: 120000 };
/** The dashboard asks the server again every 5 s. */
const REFRESHED = { timeout: 12000 };
/** Teams 09–12 are group C: the Linux room in round 1. */
const GOOD_TEAM = 8;
const PARTIAL_TEAM = 9;
const BYSTANDER = 10;

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

function field(page: Page, label: string) {
  return page.getByRole("textbox", { name: label, exact: true });
}

/** Fills the team form and presses Final submit (the dialog asks first). */
async function submitForm(page: Page, answers: Record<string, string>) {
  for (const [label, value] of Object.entries(answers)) await field(page, label).fill(value);
  await page.getByRole("button", { name: "Final submit", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Final submit", exact: true }).click();
  await expect(page.locator("event-terminal-form ui-status-chip")).toHaveText("Submitted");
}

function row(page: Page, team: string) {
  return page.locator(`tr[data-team="${team}"]`);
}

test("linux dashboard: keys, partial credit, overrides, export and the access rules", async ({ browser }) => {
  test.setTimeout(480000);
  const fixture = buildLinuxRoomFixture();
  const manager = await openRolePage(browser, "manager", WIDE);
  const good = await openRolePage(browser, "good team", WIDE);
  const partial = await openRolePage(browser, "partial team", WIDE);
  const bystander = await openRolePage(browser, "member", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  const managerApi = await EventApiSession.signIn(accounts.managers.LINUX, accounts.seedPassword);
  try {
    await setPracticeKeys(accounts, ["LINUX"]);
    await control.setRoomActivity("AI", null);
    await control.setRoomActivity("LINUX", "LINUX_TERMINAL");
    const linuxRoom = await control.roomId("LINUX");
    await managerApi.upload(`/event/rooms/${linuxRoom}/terminal-bundle`, "rami-server.zip", fixture.zip);
    await control.setRound(1);
    await signIn(good.page, accounts.members[GOOD_TEAM], accounts.seedPassword);
    await signIn(partial.page, accounts.members[PARTIAL_TEAM], accounts.seedPassword);
    await signIn(bystander.page, accounts.members[BYSTANDER], accounts.seedPassword);
    await control.startTimer();
    await control.openRoom("LINUX");

    await test.step("with no answer key the admin board warns and the dashboard says which keys are missing", async () => {
      const board = await adminApi.send<{ alerts: { code: string; message: string; link: string }[] }>("GET", "/event/admin/board");
      const alert = board.alerts.find((candidate) => candidate.code === "TERMINAL_KEYS_MISSING");
      expect(alert?.message).toContain("no answer key for PATH, USERNAME, FILE_SIZE, PATTERN, Encrypted message");
      expect(alert?.link).toBe(`/event/room/${linuxRoom}/dashboard`);
      await signIn(manager.page, accounts.managers.LINUX, accounts.seedPassword);
      await manager.page.goto(`/event/room/${linuxRoom}`);
      await manager.page.getByRole("link", { name: "Organizer dashboard", exact: true }).click();
      await expect(manager.page.getByRole("heading", { name: "Linux room dashboard", exact: true })).toBeVisible();
      await expect(manager.page.locator("[data-empty-keys]")).toContainText("PATH, USERNAME, FILE_SIZE, PATTERN, Encrypted message");
    });

    await test.step("the manager sets the keys, several values one per line", async () => {
      const page = manager.page;
      await field(page, "PATH").fill(`${fixture.target.path}\n/var/uploads/`);
      await field(page, "USERNAME").fill(fixture.target.user);
      await field(page, "FILE_SIZE").fill(fixture.target.size);
      await field(page, "PATTERN").fill(fixture.target.pattern);
      await field(page, "Encrypted message").fill(fixture.message.replace("Encrypted message: ", ""));
      await expect(page.getByText("Unsaved changes", { exact: true })).toBeVisible();
      await page.getByRole("button", { name: "Save answer keys", exact: true }).click();
      await expect(page.getByText("Answer keys saved.", { exact: false })).toBeVisible();
      await expect(page.locator("[data-empty-keys]")).toHaveCount(0);
      const board = await adminApi.send<{ alerts: { code: string }[] }>("GET", "/event/admin/board");
      expect(board.alerts.some((alert) => alert.code === "TERMINAL_KEYS_MISSING")).toBe(false);
    });

    await test.step("a correct team and a partly right team submit through the form", async () => {
      await submitForm(good.page, {
        PATH: `${fixture.target.path}/`,
        USERNAME: fixture.target.user,
        FILE_SIZE: fixture.target.size.toUpperCase(),
        PATTERN: `"${fixture.target.pattern}"`,
        "Encrypted message": `  ${fixture.message.replace("Encrypted message: ", "").replace(", ", ",   ")} `,
        "Final clue": PRACTICE_KEYS.LINUX.toLowerCase(),
      });
      await submitForm(partial.page, {
        PATH: fixture.target.path,
        USERNAME: "guard2",
        FILE_SIZE: fixture.target.size,
        PATTERN: "*.log",
        "Encrypted message": fixture.message.replace("Encrypted message: ", ""),
        "Final clue": "NOT THE CLUE",
      });
    });

    const page = manager.page;
    await test.step("the dashboard lists all active teams with totals out of 15, sorted, and refreshes by itself", async () => {
      await expect(row(page, "Team 09").locator("[data-total]")).toHaveText("15", REFRESHED);
      await expect(row(page, "Team 10").locator("[data-total]")).toHaveText("6", REFRESHED);
      await expect(row(page, "Team 09").locator("[data-status]")).toContainText("Submitted");
      await expect(row(page, "Team 11").locator("[data-status]")).toContainText("Not started");
      await expect(row(page, "Team 11").locator("[data-total]")).toHaveText("0");
      await expect(page.locator("tbody tr")).toHaveCount(16);
      // Sorted by total: the best team first, the others (0) after.
      await expect(page.locator("tbody tr").first()).toHaveAttribute("data-team", "Team 09");
      await expect(page.locator("tbody tr").nth(1)).toHaveAttribute("data-team", "Team 10");
      // Per item: the partly right team has path and size, not username or pattern, and not the message + clue item.
      for (const [item, awarded] of [["PATH", "✓"], ["USERNAME", "✗"], ["FILE_SIZE", "✓"], ["PATTERN", "✗"], ["MESSAGE_CLUE", "✗"]] as const) {
        await expect(row(page, "Team 10").locator(`[data-item="${item}"] [data-awarded]`)).toHaveText(awarded);
      }
      for (const [label, size] of [["1440", WIDE], ["390", NARROW]] as const) {
        await page.setViewportSize(size);
        await page.evaluate(() => window.scrollTo(0, 0));
        await saveScreenshot(page, path.join(shots, `linux-dashboard-${label}.png`), true);
      }
      await expectFitsPhone(page);
      await page.setViewportSize(WIDE);
    });

    await test.step("an override awards, shows Overridden, and clears; the fifth item never touches the clue", async () => {
      const teamTen = row(page, "Team 10");
      await page.getByLabel("Override USERNAME for Team 10", { exact: true }).selectOption("award");
      await expect(teamTen.locator("[data-total]")).toHaveText("9");
      await expect(teamTen.locator('[data-item="USERNAME"]')).toContainText("Overridden");
      await page.getByLabel("Override USERNAME for Team 10", { exact: true }).selectOption("auto");
      await expect(teamTen.locator("[data-total]")).toHaveText("6");
      await expect(teamTen.locator('[data-item="USERNAME"]')).not.toContainText("Overridden");

      await page.getByLabel("Override Message + clue for Team 10", { exact: true }).selectOption("award");
      await expect(teamTen.locator("[data-total]")).toHaveText("9");
      const session = await EventApiSession.fromPage(partial.page);
      try {
        const boardTen = await session.send<{ cluesCollected: number }>("GET", "/event/team/board");
        expect(boardTen.cluesCollected, "awarding the fifth item grants no clue").toBe(0);
      } finally {
        await session.dispose();
      }
      await page.getByLabel("Override Message + clue for Team 10", { exact: true }).selectOption("auto");
      await expect(teamTen.locator("[data-total]")).toHaveText("6");

      await page.getByLabel("Override Message + clue for Team 09", { exact: true }).selectOption("deny");
      await expect(row(page, "Team 09").locator("[data-total]")).toHaveText("12");
      const sessionGood = await EventApiSession.fromPage(good.page);
      try {
        const boardNine = await sessionGood.send<{ cluesCollected: number }>("GET", "/event/team/board");
        expect(boardNine.cluesCollected, "denying the fifth item removes no clue").toBe(1);
      } finally {
        await sessionGood.dispose();
      }
      await page.getByLabel("Override Message + clue for Team 09", { exact: true }).selectOption("auto");
      await expect(row(page, "Team 09").locator("[data-total]")).toHaveText("15");
    });

    await test.step("the correct clue is in the good team's collected clues, the partial team has none", async () => {
      await expect(good.page.getByText("1 of 4 clues collected", { exact: true })).toBeVisible({ timeout: 12000 });
      await expect(good.page.locator("[data-form-outcome]")).toContainText("Submitted");
      await expect(partial.page.getByText("0 of 4 clues collected", { exact: true })).toBeVisible();
    });

    await test.step("Export CSV downloads the table", async () => {
      const download = page.waitForEvent("download");
      await page.getByRole("button", { name: "Export CSV", exact: true }).click();
      const file = await download;
      expect(file.suggestedFilename()).toBe("linux-room-scores.csv");
      const csv = fs.readFileSync((await file.path()) as string, "utf8").split("\r\n");
      expect(csv[0]).toContain("pathPoints,usernamePoints");
      expect(csv.find((line) => line.startsWith('"Team 09"'))).toMatch(/,3,3,3,3,3,15,""$/);
      expect(csv.find((line) => line.startsWith('"Team 10"'))).toMatch(/,3,0,3,0,0,6,""$/);
    });

    await test.step("Test terminal starts with the room's current package", async () => {
      await page.getByRole("button", { name: "Test terminal", exact: true }).click();
      await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
      // xterm paints only what is on screen.
      await terminal(page).scrollIntoViewIfNeeded();
      await expect(terminal(page)).toContainText("Rami's server is mounted.");
    });

    await test.step("a member and another room's manager get a 404, and the keys never reach a team", async () => {
      const member = await EventApiSession.fromPage(bystander.page);
      const otherManager = await EventApiSession.signIn(accounts.managers.AI, accounts.seedPassword);
      try {
        for (const route of [`/event/rooms/${linuxRoom}/terminal-dashboard`, `/event/rooms/${linuxRoom}/terminal-dashboard.csv`]) {
          expect(await member.status(route)).toBe(404);
          expect(await otherManager.status(route)).toBe(404);
        }
        expect(await managerApi.status(`/event/rooms/${linuxRoom}/terminal-dashboard`)).toBe(200);
        expect(await adminApi.status(`/event/rooms/${linuxRoom}/terminal-dashboard`)).toBe(200);
      } finally {
        await member.dispose();
        await otherManager.dispose();
      }
    });
  } finally {
    await adminApi.dispose();
    await managerApi.dispose();
    for (const role of [manager, good, partial, bystander]) await role.page.context().close().catch(() => {});
  }
  const problems = [...manager.problems, ...good.problems, ...partial.problems, ...bystander.problems];
  expect(problems.filter((problem) => !problem.includes("404"))).toEqual([]);
});
