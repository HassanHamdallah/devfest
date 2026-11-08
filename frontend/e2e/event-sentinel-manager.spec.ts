import { test, expect, Page } from "@playwright/test";
import { randomUUID } from "node:crypto";
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
import { expectFitsPhone, openRolePage, PHONE, signIn } from "./event-ui-helper";

let accounts: RehearsalAccounts;
const shots = path.resolve("..", "docs", "ui-after");
const MISSION_FILE = path.resolve("..", "docs", "ai-room", "sentinel-content.json");
const WIDE = { width: 1440, height: 900 };
/** Teams 01 and 02 are both in group A: the AI room in round 1. */
const TEAM_ONE = 0;
const TEAM_TWO = 1;
/** One full-marks answer per stage (the shipped file's scoring), in stage order. */
const PERFECT = [
  { config: "iqr-1.5" },
  { choices: { "entry-gate": "USE", "entry-time": "USE", "vehicle-type": "USE", "previous-visits": "USE", "plate-number": "DROP", "exit-camera-time": "DROP" } },
  { config: "tree-5" },
  { k1: "Car", k5: "Van", reliable: 5 },
  { tp: 6, fp: 2, fn: 4, tn: 8, precision: 0.75, recall: 0.6, metric: "Recall" },
  { size: 6, x: 7.5, y: 6.5 },
  { order: ["lib", "lab", "dor", "adm", "gar", "g3"], path: ["lib", "lab", "adm", "gar", "g3"], cost: 15 },
  { threat: 0.00012, normal: 0.0010125, label: "Normal" },
  { conclusion: "insider-involved" },
  { config: "large-1" },
];
const TOPICS = ["Z-score", "IQR fences", "Leakage", "Overfitting", "KNN", "Confusion metrics", "K-Means", "A* search: f = g + h", "Naive Bayes", "Forward chaining", "RAG"];

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

async function shot(page: Page, name: string, fullPage = true) {
  if (process.env.DEVFEST_NO_SCREENSHOTS) return;
  const image = await page.screenshot({ fullPage });
  for (let attempt = 1; ; attempt++) {
    try {
      fs.writeFileSync(path.join(shots, name), image);
      return;
    } catch (error) {
      // OneDrive may hold a just-written file open for a moment.
      if (attempt >= 10) throw error;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
  }
}

async function noOverflow(page: Page, what: string) {
  const { scrollWidth, innerWidth } = await page.evaluate(() => ({ scrollWidth: document.documentElement.scrollWidth, innerWidth: window.innerWidth }));
  expect(scrollWidth, `horizontal overflow: ${what}`).toBeLessThanOrEqual(innerWidth + 1);
}

async function teamSession(index: number) {
  return EventApiSession.signIn(accounts.members[index], accounts.seedPassword);
}

async function submit(team: EventApiSession, room: number, stage: number) {
  await team.send("POST", `/event/team/rooms/${room}/activity/stages/${stage}/submit`, PERFECT[stage - 1], randomUUID());
}

test("sentinel manager page: import, pass score, Field Guide, live results, CSV and the manual verdict", async ({ browser }) => {
  test.setTimeout(420000);
  const manager = await openRolePage(browser, "manager", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  const page = manager.page;
  const mission = JSON.parse(fs.readFileSync(MISSION_FILE, "utf8"));
  const results = page.locator("[data-results]");
  try {
    await setPracticeKeys(accounts, ["AI", "PROBLEM_SOLVING", "LINUX", "HARDWARE"]);
    const room = await control.roomId("AI");

    await test.step("the room board links to the SENTINEL page, which starts empty", async () => {
      await signIn(page, accounts.managers.AI, accounts.seedPassword);
      await page.getByRole("link", { name: "SENTINEL page", exact: true }).click();
      await expect(page.getByRole("heading", { name: "SENTINEL · AI", exact: true })).toBeVisible();
      await expect(page.getByText("No mission yet", { exact: true })).toBeVisible();
      await expect(page.locator("[data-no-teams]")).toBeVisible();
      await expect(page.getByRole("button", { name: "Export JSON", exact: true })).toBeDisabled();
    });

    await test.step("a bad file is refused whole with every problem listed", async () => {
      const bad = structuredClone(mission);
      bad.story.failed = "You scored 59.";
      bad.missions[0].stages[0].maxPoints = 11;
      await page.locator("[data-import-file]").setInputFiles({ name: "bad-mission.json", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(bad)) });
      const dialog = page.getByRole("dialog");
      await expect(dialog).toContainText("Import bad-mission.json (10 stages)");
      await dialog.getByRole("button", { name: "Replace", exact: true }).click();
      await expect(dialog).toBeHidden();
      await expect(page.locator("[data-import-headline]")).toHaveText("The file has 3 problems.");
      const problems = page.locator("[data-problems] li");
      await expect(problems).toHaveCount(3);
      await expect(problems).toContainText(["story.failed must not contain a number.", "Stage 1: One configuration must earn the stage's full worth.", "The stages are worth 101 points in all; they must add up to 100."]);
      await expect(page.getByRole("button", { name: "Try again", exact: true })).toBeVisible();
      await expect(page.getByText("No mission yet", { exact: true })).toBeVisible();
    });

    await test.step("the right file is imported and shows ten stages worth 100", async () => {
      await page.locator("[data-import-file]").setInputFiles(MISSION_FILE);
      await page.getByRole("dialog").getByRole("button", { name: "Replace", exact: true }).click();
      await expect(page.locator("[data-notice]")).toHaveText("Imported sentinel-content.json: 10 stages.");
      await expect(page.locator("[data-stages] tbody tr")).toHaveCount(10);
      await expect(page.locator("[data-total-points]")).toHaveText("100 / 100");
      await expect(page.getByText("Mission loaded", { exact: true })).toBeVisible();
    });

    await test.step("Export JSON downloads the same mission", async () => {
      const download = page.waitForEvent("download");
      await page.getByRole("button", { name: "Export JSON", exact: true }).click();
      const file = await download;
      expect(file.suggestedFilename()).toBe("sentinel-mission.json");
      const exported = JSON.parse(fs.readFileSync((await file.path())!, "utf8"));
      expect(exported.format).toBe(1);
      expect(exported.passScore).toBe(60);
      expect(exported.missions).toEqual(mission.missions);
      expect(exported.story).toEqual(mission.story);
    });

    await test.step("the pass score is saved", async () => {
      const pass = page.getByLabel("Pass score (0–100)");
      await expect(pass).toHaveValue("60");
      await pass.fill("101");
      await page.getByRole("button", { name: "Save pass score", exact: true }).click();
      await expect(page.getByText("The pass score must be a whole number from 0 to 100.")).toBeVisible();
      await pass.fill("55");
      await page.getByRole("button", { name: "Save pass score", exact: true }).click();
      await expect(page.locator("[data-notice]")).toHaveText("Pass score saved.");
      await expect(pass).toHaveValue("55");
    });

    await test.step("the Field Guide prints on one page and holds only the ideas", async () => {
      await page.locator("[data-guide-link]").click();
      const guide = page;
      await expect(guide.getByRole("heading", { name: "SENTINEL · Field Guide", exact: true })).toBeVisible();
      for (const topic of TOPICS) await expect(guide.locator("[data-guide] h2", { hasText: topic })).toBeVisible();
      const text = (await guide.locator("[data-guide]").innerText()).toLowerCase();
      for (const word of ["white van", "tree-5", "insider", "iqr-1.5", "pass score"]) expect(text).not.toContain(word);
      await noOverflow(guide, "Field Guide at 1440");
      await shot(guide, "sentinel-field-guide-1440.png");
      await guide.emulateMedia({ media: "print" });
      await expect(guide.locator(".shell-sidebar")).toBeHidden();
      await expect(guide.locator("[data-print]")).toBeHidden();
      const pdf = (await guide.pdf({ format: "A4", printBackground: true })).toString("latin1");
      expect((pdf.match(/\/Type\s*\/Page(?![s\w])/g) ?? []).length, "the Field Guide is one printed page").toBe(1);
      await guide.setViewportSize({ width: 794, height: 1123 });
      await shot(guide, "sentinel-field-guide-print.png", false);
      await guide.emulateMedia({ media: "screen" });
      await guide.setViewportSize(PHONE);
      await noOverflow(guide, "Field Guide at 390");
      await shot(guide, "sentinel-field-guide-390.png");
      await guide.setViewportSize(WIDE);
      await guide.getByRole("link", { name: "← SENTINEL page", exact: true }).click();
      await expect(page.getByRole("heading", { name: "SENTINEL · AI", exact: true })).toBeVisible();
    });

    const one = await teamSession(TEAM_ONE);
    const two = await teamSession(TEAM_TWO);
    try {
      await test.step("live results follow two teams: one finishes all ten stages, one saves a single stage", async () => {
        await control.startRound(1);
        await control.openRoom("AI");
        await submit(two, room, 1);
        for (let stage = 1; stage <= 9; stage++) await submit(one, room, stage);
        await expect(results.locator('[data-team="Team 01"] [data-status]')).toContainText("In progress", { timeout: 15000 });
        await expect(results.locator('[data-team="Team 02"] [data-total]')).toHaveText("10");
        await expect(page.locator("[data-updated]")).toContainText("refreshes every 5 s");
        await submit(one, room, 10);
        const first = results.locator('[data-team="Team 01"]');
        await expect(first.locator("[data-status]")).toContainText("Passed", { timeout: 15000 });
        await expect(first.locator("[data-total]")).toHaveText("100");
        await expect(first.locator("[data-clue-granted]")).toHaveText("Granted");
        await expect(first.locator("[data-finished]")).not.toHaveText("—");
        await expect(results.locator("tbody tr").first()).toHaveAttribute("data-team", "Team 01");
        await expect(page.locator("[data-pass-score]")).toHaveText("55");
      });

      await test.step("at the round's end the other team is evaluated; the pass score and the import lock", async () => {
        await control.endRound();
        const second = results.locator('[data-team="Team 02"]');
        await expect(second.locator("[data-status]")).toContainText("Not passed", { timeout: 15000 });
        await expect(second.locator("[data-clue-granted]")).toHaveText("—");
        await expect(second.locator("[data-finished]")).not.toHaveText("—");
        await expect(page.locator("[data-pass-locked]")).toBeVisible();
        await expect(page.getByLabel("Pass score (0–100)")).toBeDisabled();
        await expect(page.locator("[data-import-blocked]")).toHaveText("Teams have already worked in this room; the mission can no longer be replaced.");
        await expect(page.locator("[data-import-file]")).toBeDisabled();
        await noOverflow(page, "SENTINEL page at 1440");
        await shot(page, "sentinel-manager-1440.png");
        await page.setViewportSize(PHONE);
        await noOverflow(page, "SENTINEL page at 390");
        await expectFitsPhone(page);
        await shot(page, "sentinel-manager-390.png");
        await page.setViewportSize(WIDE);
      });

      await test.step("Export CSV lists each team with its per-stage points", async () => {
        const download = page.waitForEvent("download");
        await page.getByRole("button", { name: "Export CSV", exact: true }).click();
        const file = await download;
        const lines = fs.readFileSync((await file.path())!, "utf8").split(/\r?\n/).filter(Boolean);
        expect(lines[0]).toBe("rank,team,group,stage1,stage2,stage3,stage4,stage5,stage6,stage7,stage8,stage9,stage10,total,passScore,status,clueGranted,stagesSaved,lastSavedAt,evaluatedAt");
        expect(lines[1]).toMatch(/^1,"Team 01",A,10\.00,10\.00,10\.00,10\.00,10\.00,10\.00,10\.00,10\.00,10\.00,10\.00,100\.00,55,PASSED,yes,10,/);
        expect(lines[2]).toMatch(/^2,"Team 02",A,10\.00,,,,,,,,,,10\.00,55,NOT_PASSED,no,1,/);
      });

      await test.step("the manager's manual verdict still decides the clue: Reject takes it back", async () => {
        const team = await EventApiSession.signIn(accounts.members[TEAM_ONE], accounts.seedPassword);
        try {
          expect((await team.send<{ result: { clueUnlocked: boolean; clue: string } }>("GET", `/event/team/rooms/${room}/activity`)).result.clue).toBe(PRACTICE_KEYS.AI);
          await page.goto(`/event/room/${room}`);
          const row = page.locator('[data-team="Team 01"]');
          await row.getByRole("group", { name: "Clue verdict" }).getByRole("button", { name: "Reject", exact: true }).click();
          await expect(row.getByText(/^Rejected by AI manager at /)).toBeVisible();
          expect((await team.send<{ result: { clueUnlocked: boolean } }>("GET", `/event/team/rooms/${room}/activity`)).result.clueUnlocked).toBe(false);
          await page.getByRole("link", { name: "SENTINEL page", exact: true }).click();
          const first = results.locator('[data-team="Team 01"]');
          await expect(first.locator("[data-status]")).toContainText("Passed", { timeout: 15000 });
          await expect(first.locator("[data-clue-granted]")).toHaveText("—", { timeout: 15000 });
        } finally {
          await team.dispose();
        }
      });
    } finally {
      await one.dispose();
      await two.dispose();
    }
  } finally {
    await adminApi.dispose();
    await manager.page.context().close().catch(() => {});
  }
  // The one refused import (400) is the browser's console note about it, expected by this test.
  expect(manager.problems.filter((problem) => !problem.includes("status of 400"))).toEqual([]);
});

test("sentinel manager page: another room's manager and teams cannot open it", async ({ browser }) => {
  test.setTimeout(120000);
  await setPracticeKeys(accounts, ["AI", "LINUX"]);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  try {
    const room = await control.roomId("AI");
    const linuxManager = await EventApiSession.signIn(accounts.managers.LINUX, accounts.seedPassword);
    const member = await teamSession(TEAM_ONE);
    try {
      for (const route of ["", "/export", "/results", "/results.csv"]) {
        expect(await linuxManager.status(`/event/rooms/${room}/mission${route}`), `other manager ${route}`).toBe(404);
        expect(await member.status(`/event/rooms/${room}/mission${route}`), `member ${route}`).toBe(403);
      }
    } finally {
      await linuxManager.dispose();
      await member.dispose();
    }
  } finally {
    await adminApi.dispose();
  }
});
