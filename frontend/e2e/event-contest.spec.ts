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
} from "./event-api-helper";
import { expectFitsPhone, openRolePage, saveScreenshot, signIn } from "./event-ui-helper";

let accounts: RehearsalAccounts;
const shots = path.resolve("..", "docs", "ui-after");
const WIDE = { width: 1440, height: 900 };
const NARROW = { width: 390, height: 844 };
/** The organizer page asks the server again every 5 s. */
const REFRESHED = { timeout: 12000 };
const LINK = "https://codeforces.com/contest/2200";
/** Teams 05–08 are group B: Problem solving in round 1. Team 09 (group C) is in the Linux room. */
const ASSIGNED = [4, 5, 6, 7];
const OTHER_ROOM_TEAM = 8;

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

function row(page: Page, team: string) {
  return page.locator(`tr[data-team="${team}"]`);
}

/** Types one team's result the way the organizer does: tick the problems, set the penalty, press Save. */
async function enterResult(page: Page, team: string, solved: string[], penalty: number) {
  const line = row(page, team);
  for (const problem of ["A", "B", "C", "D", "E"]) {
    const box = line.locator(`input[data-problem="${problem}"]`);
    if ((await box.isChecked()) !== solved.includes(problem)) await box.click();
  }
  await line.getByLabel(`${team} penalty in minutes`, { exact: true }).fill(String(penalty));
  await line.getByRole("button", { name: /^Save/ }).click();
  await expect(line.locator("[data-solved]")).toHaveText(String(solved.length));
}

test("contest: link, results for four teams, bulk reveal, and what each team sees", async ({ browser }) => {
  test.setTimeout(480000);
  const manager = await openRolePage(browser, "manager", WIDE);
  const teams = await Promise.all(ASSIGNED.map((index) => openRolePage(browser, `team ${index + 1}`, WIDE)));
  const [team05, team06, team07, team08] = teams;
  const elsewhere = await openRolePage(browser, "team in another room", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  try {
    await control.setRoomActivity("AI", null);
    await control.setRoomActivity("PROBLEM_SOLVING", "CONTEST_RESULTS");
    const room = await control.roomId("PROBLEM_SOLVING");

    await test.step("the admin board warns that the room has no link and no clue key", async () => {
      const board = await adminApi.send<{ alerts: { code: string; link: string }[] }>("GET", "/event/admin/board");
      const codes = board.alerts.map((alert) => alert.code);
      expect(codes).toContain("CONTEST_LINK_MISSING");
      expect(codes).toContain("CONTEST_CLUE_KEY_MISSING");
      expect(board.alerts.find((alert) => alert.code === "CONTEST_LINK_MISSING")?.link).toBe(`/event/room/${room}/contest`);
    });

    await test.step("the manager sets the clue key and the contest link", async () => {
      const page = manager.page;
      await signIn(page, accounts.managers.PROBLEM_SOLVING, accounts.seedPassword);
      await expect(page.getByRole("heading", { name: "Problem solving", exact: true }).first()).toBeVisible();
      const card = page.locator("[data-clue-key-card]");
      await card.getByRole("button", { name: "Set clue key", exact: true }).click();
      await card.getByRole("textbox", { name: "Clue key", exact: true }).fill(PRACTICE_KEYS.PROBLEM_SOLVING);
      await card.getByRole("button", { name: "Save key", exact: true }).click();
      await expect(card.getByText("Saved. Re-checked 0 answers.", { exact: true })).toBeVisible();

      await page.getByRole("link", { name: "Contest results", exact: true }).click();
      await expect(page.getByRole("heading", { name: "Problem Solving results", exact: true })).toBeVisible();
      await expect(page.locator("[data-link-missing]")).toBeVisible();
      await expect(page.locator("[data-clue-key-missing]")).toHaveCount(0);
      await page.getByRole("textbox", { name: "Contest link", exact: true }).fill("http://codeforces.com/contest/2200");
      await page.getByRole("button", { name: "Save link", exact: true }).click();
      await expect(page.getByText("The contest link must start with https://.", { exact: true })).toBeVisible();
      await page.getByRole("textbox", { name: "Contest link", exact: true }).fill(LINK);
      await page.getByRole("button", { name: "Save link", exact: true }).click();
      await expect(page.getByText("Contest link saved.", { exact: false })).toBeVisible();
      await expect(page.locator("[data-link-missing]")).toHaveCount(0);
      const board = await adminApi.send<{ alerts: { code: string }[] }>("GET", "/event/admin/board");
      expect(board.alerts.some((alert) => alert.code.startsWith("CONTEST_"))).toBe(false);
    });

    await test.step("the admin starts round 1: only the assigned teams see the link", async () => {
      await control.setRound(1);
      for (const [position, index] of ASSIGNED.entries()) {
        await signIn(teams[position].page, accounts.members[index], accounts.seedPassword);
      }
      await signIn(elsewhere.page, accounts.members[OTHER_ROOM_TEAM], accounts.seedPassword);
      await control.openRoom("PROBLEM_SOLVING");
      // Before the timer starts there is no link yet.
      await expect(team05.page.locator("[data-contest-waiting]")).toBeVisible();
      await expect(team05.page.locator("[data-contest-link]")).toHaveCount(0);
      await control.startTimer();
      for (const role of teams) {
        await expect(role.page.locator("[data-contest-link]")).toHaveAttribute("href", LINK);
        await expect(role.page.locator("[data-contest-review]")).toHaveText("Your results are being reviewed by the room organizer.");
        await expect(role.page.getByText("Clue unlocked", { exact: false })).toHaveCount(0);
      }
      await expect(elsewhere.page.locator("[data-contest-link]")).toHaveCount(0);
      await expect(elsewhere.page.getByText("Problem Solving contest", { exact: false })).toHaveCount(0);
      const other = await EventApiSession.fromPage(elsewhere.page);
      try {
        expect(await other.status(`/event/team/rooms/${room}/contest`)).toBe(404);
      } finally {
        await other.dispose();
      }
      for (const [label, size] of [["1440", WIDE], ["390", NARROW]] as const) {
        await team05.page.setViewportSize(size);
        await team05.page.evaluate(() => window.scrollTo(0, 0));
        await saveScreenshot(team05.page, path.join(shots, `contest-team-${label}.png`), true);
      }
      await expectFitsPhone(team05.page);
      await team05.page.setViewportSize(WIDE);
    });

    const page = manager.page;
    await test.step("the manager enters the results of four teams from the standings", async () => {
      await page.reload();
      await expect(page.locator('[data-round="1"]')).toBeVisible();
      await enterResult(page, "Team 05", ["A", "B", "C", "D", "E"], 120);
      await enterResult(page, "Team 06", ["A", "B", "C"], 60);
      await enterResult(page, "Team 07", ["A", "B"], 10);
      await enterResult(page, "Team 08", ["A", "B", "C"], 60);
    });

    await test.step("points, bonus, total, place and eligibility are worked out and sorted by place", async () => {
      const round = page.locator('[data-round="1"]');
      await expect(row(page, "Team 05").locator("[data-total]")).toHaveText("15");
      await expect(row(page, "Team 05").locator("[data-bonus]")).toHaveText("+3");
      await expect(row(page, "Team 06").locator("[data-total]")).toHaveText("10");
      await expect(row(page, "Team 08").locator("[data-total]")).toHaveText("10");
      await expect(row(page, "Team 07").locator("[data-total]")).toHaveText("5");
      await expect(row(page, "Team 06").locator("[data-place]")).toHaveText("2");
      await expect(row(page, "Team 08").locator("[data-place]")).toHaveText("2");
      await expect(row(page, "Team 07").locator("[data-place]")).toHaveText("4");
      await expect(row(page, "Team 07").locator("[data-eligible]")).toContainText("Not yet");
      await expect(row(page, "Team 06").locator("[data-eligible]")).toContainText("Eligible");
      const order = await round.locator("tbody tr").evaluateAll((rows) => rows.map((tr) => tr.getAttribute("data-team")));
      expect(order.slice(0, 4)).toEqual(["Team 05", "Team 06", "Team 08", "Team 07"]);
      // A team that is not eligible cannot be revealed from the page.
      await expect(row(page, "Team 07").getByRole("button", { name: "Reveal clue", exact: true })).toBeDisabled();
      for (const [label, size] of [["1440", WIDE], ["390", NARROW]] as const) {
        await page.setViewportSize(size);
        await page.evaluate(() => window.scrollTo(0, 0));
        await saveScreenshot(page, path.join(shots, `contest-organizer-${label}.png`), true);
      }
      await expectFitsPhone(page);
      await page.setViewportSize(WIDE);
    });

    await test.step("the page picks up a change made elsewhere by itself", async () => {
      const managerApi = await EventApiSession.signIn(accounts.managers.PROBLEM_SOLVING, accounts.seedPassword);
      try {
        const view = await managerApi.send<{ rounds: { teams: { teamId: number; teamName: string }[] }[] }>("GET", `/event/rooms/${room}/contest-results`);
        const first = view.rounds.flatMap((round) => round.teams).find((team) => team.teamName === "Team 01");
        expect(first).toBeTruthy();
        await managerApi.send("PUT", `/event/rooms/${room}/contest-results/teams/${first!.teamId}`, { problems: ["A", "B", "C", "D"], penaltyMinutes: 33 });
      } finally {
        await managerApi.dispose();
      }
      await expect(row(page, "Team 01").locator("[data-total]")).toHaveText("13", REFRESHED);
    });

    await test.step("Export CSV downloads the table", async () => {
      const download = page.waitForEvent("download");
      await page.getByRole("button", { name: "Export CSV", exact: true }).click();
      const file = await download;
      expect(file.suggestedFilename()).toBe("problem-solving-results.csv");
      const csv = fs.readFileSync((await file.path()) as string, "utf8").split("\r\n");
      expect(csv[0]).toBe("round,group,place,team,saved,solved,solvedCount,penaltyMinutes,problemPoints,bonus,total,eligible,released");
      expect(csv.find((line) => line.includes('"Team 05"'))).toBe('1,B,1,"Team 05",true,"A B C D E",5,120,12,3,15,true,false');
    });

    await test.step("Reveal to all eligible releases the clue to round 1's three eligible teams", async () => {
      await page.getByRole("button", { name: /^Reveal to all eligible/ }).first().scrollIntoViewIfNeeded();
      const round = page.locator('[data-round="1"]');
      await round.getByRole("button", { name: "Reveal to all eligible (3)", exact: true }).click();
      await expect(page.getByText("Round 1: clue released to 3 teams.", { exact: true })).toBeVisible();
      for (const team of ["Team 05", "Team 06", "Team 08"]) await expect(row(page, team).locator("[data-released]")).toHaveAttribute("data-released", "true");
      await expect(row(page, "Team 07").locator("[data-released]")).toHaveAttribute("data-released", "false");
      await expect(round.getByRole("button", { name: /^Reveal to all eligible/ })).toBeDisabled();
    });

    await test.step("eligible teams see Clue unlocked with the clue; the other team does not", async () => {
      for (const role of [team05, team06, team08]) {
        const result = role.page.locator('[data-contest-result="unlocked"]');
        await expect(result).toBeVisible();
        await expect(result.getByRole("heading", { name: "Clue unlocked", exact: true })).toBeVisible();
        await expect(result.locator("[data-clue-value]")).not.toContainText(PRACTICE_KEYS.PROBLEM_SOLVING);
        await result.getByRole("button", { name: "Show clue", exact: true }).click();
        await expect(result.locator("[data-clue-value]")).toHaveText(PRACTICE_KEYS.PROBLEM_SOLVING);
        await expect(role.page.locator('[data-evidence="PROBLEM_SOLVING"] [data-contest-outcome]')).toContainText("Clue unlocked");
        // A room without an answer form records the result itself: nothing was "Submitted", the room is complete.
        await expect(role.page.locator('[data-room="PROBLEM_SOLVING"]')).toContainText("Room complete");
        await expect(role.page.locator('[data-room="PROBLEM_SOLVING"]')).not.toContainText("Submitted");
        const text = (await role.page.locator("event-contest-room-activity").innerText()).toLowerCase();
        for (const word of ["points", "score", "total", "penalty", "place", "eligible", "bonus"]) expect(text).not.toContain(word);
      }
      for (const [label, size] of [["1440", WIDE], ["390", NARROW]] as const) {
        await team05.page.setViewportSize(size);
        await team05.page.evaluate(() => window.scrollTo(0, 0));
        await saveScreenshot(team05.page, path.join(shots, `contest-team-unlocked-${label}.png`), true);
      }
      await expectFitsPhone(team05.page);
      await team05.page.setViewportSize(WIDE);

      await expect(team07.page.locator('[data-contest-result="unlocked"]')).toHaveCount(0);
      await expect(team07.page.getByText("Clue unlocked", { exact: false })).toHaveCount(0);
      await expect(team07.page.locator("[data-contest-review]")).toBeVisible();
      const api = await EventApiSession.fromPage(team07.page);
      try {
        const raw = JSON.stringify(await api.send("GET", "/event/team/board")) + JSON.stringify(await api.send("GET", `/event/team/rooms/${room}/contest`));
        expect(raw).not.toContain(PRACTICE_KEYS.PROBLEM_SOLVING);
        expect(raw).not.toMatch(/"(solved|solvedCount|penalty|penaltyMinutes|points|problemPoints|bonus|total|place|eligible)"\s*:/);
      } finally {
        await api.dispose();
      }
    });

    await test.step("a refresh keeps every state", async () => {
      await page.reload();
      await expect(row(page, "Team 05").locator("[data-total]")).toHaveText("15");
      await expect(row(page, "Team 05").locator("[data-released]")).toHaveAttribute("data-released", "true");
      await expect(row(page, "Team 07").locator("[data-released]")).toHaveAttribute("data-released", "false");
      await expect(page.getByRole("textbox", { name: "Contest link", exact: true })).toHaveValue(LINK);
      await team05.page.reload();
      await expect(team05.page.locator('[data-contest-result="unlocked"]')).toBeVisible();
      await team07.page.reload();
      await expect(team07.page.locator("[data-contest-link]")).toHaveAttribute("href", LINK);
      await expect(team07.page.locator("[data-contest-review]")).toBeVisible();
      await expect(team07.page.locator('[data-contest-result="unlocked"]')).toHaveCount(0);
    });

    await test.step("editing results later never changes a clue; the page warns about a released team that no longer qualifies", async () => {
      await enterResult(page, "Team 06", ["A"], 60);
      await expect(row(page, "Team 06").locator("[data-warning]")).toContainText("Released, but no longer eligible");
      await expect(team06.page.locator('[data-contest-result="unlocked"]')).toBeVisible();
    });
  } finally {
    await adminApi.dispose();
    for (const role of [manager, ...teams, elsewhere]) await role.page.context().close().catch(() => {});
  }
  const problems = [manager, ...teams, elsewhere].flatMap((role) => role.problems);
  // The 404s are the deliberate refusals; the one 400 is the http link the manager typed on purpose.
  expect(problems.filter((problem) => !problem.includes("404") && !problem.includes("400 (Bad Request)"))).toEqual([]);
});
