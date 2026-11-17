import { test, expect, Locator, Page } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {
  EventAdminShortcuts,
  EventApiSession,
  RehearsalAccounts,
  readRehearsalAccounts,
  resetRehearsal,
  setPracticeKeys,
} from "./event-api-helper";
import { ROOMS, ScoreRow, finalContentJson, finalPackageZip, teamName } from "./event-final-helper";
import { PHONE, expectFitsPhone, openRolePage, saveScreenshot, screenshotPhone, signIn } from "./event-ui-helper";

const WIDE = { width: 1440, height: 900 };
const docs = path.resolve("..", "docs");
const shot = (name: string) => path.join(docs, `final-${name}.png`);
const POLL = { timeout: 25000 };
const REASON = "Copied the neighbour's output";

let accounts: RehearsalAccounts;

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

async function shootBoth(page: Page, name: string) {
  await saveScreenshot(page, shot(`${name}-1440`));
  await expectFitsPhone(page);
  await screenshotPhone(page, shot(`${name}-390`));
}

function card(page: Page, team: string): Locator {
  return page.locator(`event-room-team-row[data-team="${team}"]`);
}

async function cancel(row: Locator, kind: "HINT" | "CHEATING", reason: string) {
  const item = row.locator(`[data-deduction="${kind}"][data-cancelled="false"]`);
  await item.getByRole("button", { name: kind === "HINT" ? "Cancel hint" : "Cancel cheating", exact: true }).click();
  await item.getByRole("textbox", { name: "Cancellation reason", exact: true }).fill(reason);
  await item.getByRole("button", { name: "Confirm cancellation", exact: true }).click();
  await expect(row.locator(`[data-deduction="${kind}"][data-cancelled="true"]`)).toBeVisible();
}

function adminTotal(page: Page, team: string): Locator {
  return page.locator(`[data-scoreboard] tr[data-team="${team}"] [data-total]`);
}

async function totals(admin: EventApiSession) {
  const rows = await admin.send<ScoreRow[]>("GET", "/event/admin/scoreboard");
  return new Map(rows.map((row) => [row.scores.teamName, row.scores]));
}

test("scores: hint and cheating from the manager's card change the totals; cancelling restores them; the team never sees cheating", async ({
  browser,
}) => {
  test.setTimeout(900000);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const manager = await openRolePage(browser, "AI manager", WIDE);
  const admin = await openRolePage(browser, "admin", WIDE);
  const team = await openRolePage(browser, "team 01", PHONE);
  try {
    const control = new EventAdminShortcuts(adminApi);
    await control.setRoomActivity("AI", null);
    await setPracticeKeys(accounts, ROOMS);
    await control.startRound(1);
    await control.openRoom("AI");

    await signIn(manager.page, accounts.managers.AI, accounts.seedPassword);
    await expect(manager.page.getByRole("heading", { level: 1, name: "AI", exact: true })).toBeVisible();
    const row = card(manager.page, teamName(1));

    await test.step("the manager types the room score; the admin scoreboard follows", async () => {
      await row.getByRole("button", { name: "Details for Team 01", exact: true }).click();
      await row.getByRole("spinbutton", { name: "Room score (0 to 15)", exact: true }).fill("15");
      await row.getByRole("button", { name: "Save score", exact: true }).click();
      await expect(row.locator("[data-manual-score]")).toBeVisible();
      await signIn(admin.page, accounts.adminEmail, accounts.adminPassword);
      await admin.page.goto("/event/admin/scores");
      await expect(adminTotal(admin.page, teamName(1))).toHaveText("15", POLL);
    });

    await test.step("Hint given (-2): the card says Hints: 1 of 2 and the total drops by 2", async () => {
      await expect(row.locator("[data-hints-count]")).toHaveText("Hints: 0 of 2");
      await row.getByRole("button", { name: "Hint given (−2)", exact: true }).click();
      await expect(row.locator("[data-hints-count]")).toHaveText("Hints: 1 of 2");
      // The count shows once on a current team's card (the bar), not again in the summary line.
      await expect(row.getByText(/Hints: \d of 2/)).toHaveCount(1);
      await expect(row.locator("[data-hints-summary]")).toHaveCount(0);
      await expect(adminTotal(admin.page, teamName(1))).toHaveText("13", POLL);
      await saveScreenshot(manager.page, shot("manager-card-1440"));
    });

    await test.step("Cheating (-7.5) needs a confirmation and a reason; the total drops by 7.5", async () => {
      await row.getByRole("button", { name: "Cheating (−7.5)", exact: true }).click();
      await row.getByRole("button", { name: "Confirm cheating (−7.5)", exact: true }).click();
      await expect(manager.page.getByText("Give a reason (3 to 500 characters).")).toBeVisible();
      await row.getByRole("textbox", { name: "Cheating reason", exact: true }).fill(REASON);
      await row.getByRole("button", { name: "Confirm cheating (−7.5)", exact: true }).click();
      await expect(row.locator("[data-cheating-active]")).toBeVisible();
      await expect(row.getByRole("button", { name: "Cheating (−7.5)", exact: true })).toBeDisabled();
      await expect(adminTotal(admin.page, teamName(1))).toHaveText("5.5", POLL);
      await expect(admin.page.locator(`tr[data-team="${teamName(1)}"] [data-room="AI"] [data-deduction-line]`)).toBeVisible(POLL);
      await expect(row).toContainText(REASON);
    });

    await test.step("the team sees only Hints used: 1 of 2, never the cheating record, a deduction or a score", async () => {
      await signIn(team.page, accounts.members[0], accounts.seedPassword);
      await expect(team.page.locator("[data-hints-used]")).toHaveText("Hints used: 1 of 2");
      for (const url of ["/event/team", "/event/team/final"]) {
        await team.page.goto(url);
        await expect(team.page.locator("body")).not.toContainText(/cheat|7\.5|neighbour|deduct|score|points/i);
      }
      await team.page.goto("/event/team");
      await expect(team.page.locator("[data-hints-used]")).toBeVisible();
      await expectFitsPhone(team.page);
      await screenshotPhone(team.page, shot("team-hints-390"));
    });

    await test.step("cancelling the hint and then the cheating restores the points", async () => {
      await cancel(row, "HINT", "Pressed by mistake");
      await expect(row.locator("[data-hints-count]")).toHaveText("Hints: 0 of 2");
      await expect(adminTotal(admin.page, teamName(1))).toHaveText("7.5", POLL);
      await cancel(row, "CHEATING", "Recorded for the wrong team");
      await expect(row.locator("[data-cheating-active]")).toHaveCount(0);
      await expect(adminTotal(admin.page, teamName(1))).toHaveText("15", POLL);
      // The cancelled rows stay in the log with who cancelled them and why.
      await expect(row.locator('[data-cancelled-line]').first()).toContainText("Cancelled by");
      const mine = (await totals(adminApi)).get(teamName(1))!;
      expect(mine.total).toBe(15);
    });

    await test.step("a team has two hints in total; the third is refused", async () => {
      const hint = row.getByRole("button", { name: "Hint given (−2)", exact: true });
      await hint.click();
      await expect(row.locator("[data-hints-count]")).toHaveText("Hints: 1 of 2");
      await hint.click();
      await expect(row.locator("[data-hints-count]")).toHaveText("Hints: 2 of 2");
      await expect(hint).toBeDisabled();
      await expect(row).toContainText("This team has used its 2 hints.");
      await expect(adminTotal(admin.page, teamName(1))).toHaveText("11", POLL);
    });

    await test.step("the admin scoreboard, final table and penalty log show it all", async () => {
      await admin.page.reload();
      await expect(adminTotal(admin.page, teamName(1))).toHaveText("11");
      await expect(admin.page.getByRole("table", { name: "Scoreboard" }).locator("tr[data-team='Team 01'] [data-room='AI']")).toContainText("15 − 4");
      await shootBoth(admin.page, "admin-scoreboard");
      await admin.page.getByRole("tab", { name: "Final", exact: true }).click();
      await expect(admin.page.locator("[data-final-table] tr[data-team='Team 01']")).toBeVisible();
      await shootBoth(admin.page, "admin-final-table");
      await admin.page.getByRole("tab", { name: "Penalty log", exact: true }).click();
      const log = admin.page.locator("[data-deduction-log]");
      await expect(log.locator('[data-deduction="CHEATING"][data-cancelled="true"]')).toContainText("Recorded for the wrong team");
      await expect(log.locator('[data-deduction="HINT"][data-cancelled="true"]')).toContainText("Pressed by mistake");
      await expect(log.locator('[data-deduction="HINT"][data-cancelled="false"]')).toHaveCount(2);
      await shootBoth(admin.page, "admin-penalty-log");
    });

    await test.step("the admin gives a hint to any team in any room from the team panel", async () => {
      await admin.page.getByRole("tab", { name: "Scoreboard", exact: true }).click();
      await admin.page.locator("[data-scoreboard] tr[data-team='Team 05']").getByRole("button").click();
      const panel = admin.page.locator("[data-drawer-deductions]");
      await panel.getByLabel("Room", { exact: true }).selectOption({ label: "Hardware room" });
      await panel.getByRole("button", { name: "Hint given (−2)", exact: true }).click();
      await expect(panel.locator("[data-hints-count]")).toHaveText("Hints: 1 of 2");
      await saveScreenshot(admin.page, shot("admin-team-panel-1440"));
      const score = (await totals(adminApi)).get(teamName(5))!;
      expect(score.rooms.find((room) => room.code === "HARDWARE")!.score).toBe(0);
      await expect.poll(async () => (await adminApi.send<{ teamName: string }[]>("GET", "/event/admin/deductions")).length).toBe(5);
    });

    await test.step("the CSV download follows the scoreboard", async () => {
      await admin.page.keyboard.press("Escape");
      const download = admin.page.waitForEvent("download");
      await admin.page.getByRole("button", { name: "Download CSV", exact: true }).click();
      const file = await download;
      expect(file.suggestedFilename()).toBe("devfest-event-results.csv");
      const text = fs.readFileSync((await file.path())!, "utf8");
      expect(text.split("\r\n")[0]).toContain("roomsTotal");
      expect(text).toContain("Team 01");
    });

    await test.step("after publication the team's ranking shows scores but no deduction or cheating", async () => {
      await adminApi.send("PUT", "/event/admin/publication", { published: true });
      await team.page.goto("/event/results");
      await expect(team.page.getByRole("table", { name: "Ranking" })).toBeVisible(POLL);
      await expect(team.page.locator("body")).not.toContainText(/cheat|deduct|neighbour/i);
      await expectFitsPhone(team.page);
    });
  } finally {
    await adminApi.dispose();
    await Promise.all([manager, admin, team].map((role) => role.page.context().close()));
  }
  expect([...manager.problems, ...admin.problems, ...team.problems].filter((problem) => !/40[0-9] \(Conflict\)|429/.test(problem))).toEqual([]);
});

test("scores: the admin imports the final content, sees every problem at once, and controls the one final timer", async ({ browser }) => {
  test.setTimeout(900000);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const admin = await openRolePage(browser, "admin", WIDE);
  try {
    await signIn(admin.page, accounts.adminEmail, accounts.adminPassword);
    await admin.page.goto("/event/admin/content");
    const section = admin.page.locator("[data-final-content]");
    await expect(section).toContainText("Not imported");
    const good = fs.readFileSync(path.resolve("..", "docs", "final", "last-shipment-content.json"));

    await test.step("a broken file is refused whole, with every problem listed", async () => {
      const broken = good.toString("utf8").replace('"format": 2', '"format": 1').replace('"correctSuspect": "S11"', '"correctSuspect": "S99"');
      await section.locator("[data-final-content-file]").setInputFiles({ name: "broken.json", mimeType: "application/json", buffer: Buffer.from(broken) });
      await section.getByRole("button", { name: "Import this file", exact: true }).click();
      const problems = section.locator("[data-final-problems]");
      await expect(problems).toContainText("format: must be 2.");
      await expect(problems).toContainText("correctSuspect: must be the id of one of the suspects.");
      await expect(section).toContainText("Not imported");
    });

    await test.step("the shipped file imports, and no placeholder Recovery Challenge is flagged", async () => {
      await section.locator("[data-final-content-file]").setInputFiles({ name: "last-shipment-content.json", mimeType: "application/json", buffer: good });
      await section.getByRole("button", { name: "Import this file", exact: true }).click();
      await expect(section.locator("[data-final-imported]")).toBeVisible();
      await expect(section).toContainText("Imported");
      await expect(section.locator("[data-final-content-counts]")).toHaveText("Format 2 · 4 clues · 12 suspects");
      await expect(section.locator("[data-recovery-placeholder]")).toHaveCount(0);
      await admin.page.goto("/event/admin");
      await expect(admin.page.getByRole("heading", { name: "Alerts", exact: true })).toBeVisible();
      await expect(admin.page.locator('[data-alert="RECOVERY_PLACEHOLDER"]')).toHaveCount(0);
      await admin.page.goto("/event/admin/content");
      await shootBoth(admin.page, "admin-content-import");
    });

    await test.step("the final terminal package is uploaded on its own card and its alert goes away", async () => {
      await admin.page.goto("/event/admin");
      await expect(admin.page.locator('[data-alert="MISSING_FINAL_TERMINAL_PACKAGE"]')).toHaveCount(1);
      await admin.page.goto("/event/admin/content");
      const card = admin.page.locator("[data-final-package]");
      await expect(card).toContainText("Not uploaded");
      await card.locator('input[type="file"][accept^=".zip"]').setInputFiles({
        name: "final-package.zip",
        mimeType: "application/zip",
        buffer: finalPackageZip(),
      });
      await expect(card.locator("[data-final-package-notice]")).toContainText("Uploaded the final terminal package (final-package.zip).");
      await expect(card.locator(".terminal-files__summary")).toContainText("12 · ");
      await expect(card.locator("[data-final-package-file]")).toHaveText("File: final-package.zip");
      await expect(card.locator("[data-package-sha]")).toHaveText(/^[0-9a-f]{12}$/);
      await shootBoth(admin.page, "admin-final-package");
      await admin.page.goto("/event/admin");
      await expect(admin.page.getByRole("heading", { name: "Alerts", exact: true })).toBeVisible();
      await expect(admin.page.locator('[data-alert="MISSING_FINAL_TERMINAL_PACKAGE"]')).toHaveCount(0);
    });

    await test.step("with the real Recovery Challenge the final opens, and the timer controls apply to everyone", async () => {
      await setPracticeKeys(accounts, ROOMS);
      await adminApi.send("PUT", "/event/admin/final/content", JSON.parse(finalContentJson()));
      await new EventAdminShortcuts(adminApi).openFinal();
      await admin.page.goto("/event/admin");
      const panel = admin.page.locator("[data-final-timer-panel]");
      await expect(panel.locator("[data-final-timer-phase]")).toHaveText("Running", POLL);
      await panel.getByRole("button", { name: "Pause final", exact: true }).click();
      await expect(panel.locator("[data-final-timer-phase]")).toHaveText("Paused");
      expect((await adminApi.send<{ phase: string }>("GET", "/event/admin/final/timer")).phase).toBe("PAUSED");
      await panel.getByRole("button", { name: "Resume final", exact: true }).click();
      await expect(panel.locator("[data-final-timer-phase]")).toHaveText("Running");
      await panel.getByRole("button", { name: "+5 min", exact: true }).click();
      await expect(panel).toContainText("5 minutes added for every team.");
      expect((await adminApi.send<{ durationSeconds: number }>("GET", "/event/admin/final/timer")).durationSeconds).toBe(2700);
      await saveScreenshot(admin.page, shot("admin-final-timer-1440"));
      await expectFitsPhone(admin.page);
    });

    await test.step("the content is read-only once the final has opened", async () => {
      await admin.page.goto("/event/admin/content");
      await expect(admin.page.locator("[data-final-content]")).toContainText("Event content is read-only after the final opens.");
      await expect(admin.page.locator("[data-final-content-file]")).toHaveCount(0);
      await expect(admin.page.locator("[data-final-package]")).toContainText("Event content is read-only after the final opens.");
      await expect(admin.page.locator('[data-final-package] input[type="file"]')).toHaveCount(0);
    });
  } finally {
    await adminApi.dispose();
    await admin.page.context().close();
  }
  // The refused broken file is an expected 400 from the import endpoint (the browser logs every failed request).
  expect(
    admin.problems.filter((problem) => !/status of 400 \(Bad Request\) \(.*\/admin\/final\/content\)/.test(problem)),
  ).toEqual([]);
});
