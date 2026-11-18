import { test, expect } from "@playwright/test";
import path from "node:path";
import {
  EventAdminShortcuts,
  EventApiSession,
  RehearsalAccounts,
  readRehearsalAccounts,
  resetRehearsal,
} from "./event-api-helper";
import { expectFitsPhone, openRolePage, saveScreenshot, signIn } from "./event-ui-helper";

let accounts: RehearsalAccounts;
const shots = path.resolve("..", "docs", "ui-after");
const WIDE = { width: 1440, height: 900 };
const NARROW = { width: 390, height: 844 };
const PHONE = "+970-59-881";
/** Not the phone number: the room's existing key, which a correct number releases. */
const KEY = "HW-KEY-4471";
/** Teams 13–16 are group D: the Hardware room in round 1. */
const TEAM_ONE = 12;
const TEAM_TWO = 13;

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

test("hardware room: points out of 100 step by step, and a phone number answer that releases the Retrieved key", async ({
  browser,
}) => {
  test.setTimeout(300000);
  const manager = await openRolePage(browser, "manager", WIDE);
  const teamOne = await openRolePage(browser, "team 13", WIDE);
  const teamTwo = await openRolePage(browser, "team 14", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  try {
    await control.setRoomActivity("AI", null);
    await control.setRoomActivity("HARDWARE", "PHONE_POINTS");
    const room = await control.roomId("HARDWARE");
    const page = manager.page;

    await test.step("the admin board warns that the room has no phone number", async () => {
      const board = await adminApi.send<{ alerts: { code: string; link: string }[] }>("GET", "/event/admin/board");
      expect(board.alerts.find((alert) => alert.code === "PHONE_NUMBER_MISSING")?.link).toBe(`/event/room/${room}`);
    });

    await test.step("the manager sets the Retrieved key and the phone number", async () => {
      await signIn(page, accounts.managers.HARDWARE, accounts.seedPassword);
      const keyCard = page.locator("[data-clue-key-card]");
      await expect(keyCard.getByRole("heading", { name: "Retrieved key", exact: true })).toBeVisible();
      await keyCard.getByRole("button", { name: "Set retrieved key", exact: true }).click();
      await keyCard.getByRole("textbox", { name: "Retrieved key", exact: true }).fill(KEY);
      await keyCard.getByRole("button", { name: "Save key", exact: true }).click();
      await expect(keyCard.getByText("Saved. Re-checked 0 answers.", { exact: true })).toBeVisible();

      const card = page.locator("[data-phone-number-card]");
      await expect(card).toContainText("Not set");
      await card.getByRole("button", { name: "Set phone number", exact: true }).click();
      await card.getByRole("textbox", { name: "Phone number (answer)", exact: true }).fill(PHONE);
      await card.getByRole("button", { name: "Save phone number", exact: true }).click();
      await expect(card.locator("[data-phone-set]")).toBeVisible();
      await card.getByRole("button", { name: "Show", exact: true }).click();
      await expect(card.locator("[data-phone-value]")).toHaveText(PHONE);
      const board = await adminApi.send<{ alerts: { code: string }[] }>("GET", "/event/admin/board");
      expect(board.alerts.some((alert) => alert.code === "PHONE_NUMBER_MISSING")).toBe(false);
    });

    await test.step("round 1 starts and the Hardware room opens", async () => {
      await control.setRound(1);
      await signIn(teamOne.page, accounts.members[TEAM_ONE], accounts.seedPassword);
      await signIn(teamTwo.page, accounts.members[TEAM_TWO], accounts.seedPassword);
      await control.startTimer();
      await control.openRoom("HARDWARE");
      await page.reload();
    });

    const row = page.locator('event-room-team-row[data-team="Team 13"]');
    const line = row.locator("[data-points-line]");
    const box = row.getByRole("spinbutton", { name: "Points", exact: true });

    await test.step("the manager adds 30, adds 40 (Enter), subtracts 5 and undoes the last entry: 70 / 100", async () => {
      await expect(line).toContainText("0 / 100");
      await box.fill("30");
      await row.getByRole("button", { name: "Add", exact: true }).click();
      await expect(line).toContainText("30 / 100");
      await box.fill("40");
      await box.press("Enter");
      await expect(line).toContainText("70 / 100");
      await box.fill("5");
      await row.getByRole("button", { name: "Subtract", exact: true }).click();
      await expect(line).toContainText("65 / 100");
      await expect(line).toContainText("(= 9.75 / 15)");
      await row.getByRole("button", { name: "Undo -5", exact: true }).click();
      await expect(line).toContainText("70 / 100");
      await expect(line).toContainText("(= 10.50 / 15)");
      await expect(row.getByRole("button", { name: "Undo +40", exact: true })).toBeVisible();
      // The old 0-15 score input is gone in this room.
      await expect(page.locator("[data-manual-score]")).toHaveCount(0);
    });

    await test.step("a change that would leave 0..100 is refused with the current total; the history lists every entry", async () => {
      await box.fill("40");
      await row.getByRole("button", { name: "Add", exact: true }).click();
      await expect(row.getByText("The total must stay between 0 and 100 (now 70).", { exact: true })).toBeVisible();
      await expect(line).toContainText("70 / 100");
      await row.locator("[data-points-history] summary").click();
      await expect(row.locator("[data-points-history] tbody tr")).toHaveCount(4);
      await expect(row.locator("[data-points-history] tbody tr").first()).toContainText("+5 (undo)");
      await expect(row.locator("[data-points-history] tbody tr").first()).toContainText("70");
    });

    await test.step("the board is readable on a phone", async () => {
      for (const [label, size] of [["1440", WIDE], ["390", NARROW]] as const) {
        await page.setViewportSize(size);
        await row.scrollIntoViewIfNeeded();
        await saveScreenshot(page, path.join(shots, `hardware-board-${label}.png`), false);
      }
      await expectFitsPhone(page);
      await page.setViewportSize(WIDE);
    });

    await test.step("a team that types the number in another format gets the room's existing key as its Retrieved key", async () => {
      const team = teamOne.page;
      const answer = team.getByRole("textbox", { name: "Phone number", exact: true });
      await expect(answer).toBeVisible();
      await expect(team.getByRole("textbox", { name: "Technical output", exact: true })).toHaveCount(0);
      await answer.fill("970 59 881");
      await team.getByRole("button", { name: "Submit answer", exact: true }).click();
      const key = team.locator("[data-retrieved-key]");
      await expect(key).toBeVisible();
      await key.getByRole("button", { name: "Show clue", exact: true }).click();
      await expect(key.locator("[data-clue-value]")).toHaveText(KEY);
      await saveScreenshot(team, path.join(shots, "hardware-team-1440.png"), false);
      const api = await EventApiSession.fromPage(team);
      try {
        const raw = JSON.stringify(await api.send("GET", "/event/team/board"));
        expect(raw).not.toContain(PHONE);
        expect(raw).not.toContain("pointsTotal");
      } finally {
        await api.dispose();
      }
    });

    await test.step("another team with a wrong number is told so and gets no key", async () => {
      const team = teamTwo.page;
      await team.getByRole("textbox", { name: "Phone number", exact: true }).fill("970 59 882");
      await team.getByRole("button", { name: "Submit answer", exact: true }).click();
      await expect(team.getByText("That number is not correct.", { exact: true })).toBeVisible();
      await expect(team.locator("[data-retrieved-key]")).toHaveCount(0);
      const api = await EventApiSession.fromPage(team);
      try {
        expect(JSON.stringify(await api.send("GET", "/event/team/board"))).not.toContain(KEY);
      } finally {
        await api.dispose();
      }
    });
  } finally {
    await adminApi.dispose();
    for (const role of [manager, teamOne, teamTwo]) await role.page.context().close().catch(() => {});
  }
  expect([...manager.problems, ...teamOne.problems, ...teamTwo.problems].filter((problem) => !problem.includes("400"))).toEqual([]);
});
