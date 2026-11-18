import { test, expect, Locator, Page } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { EventAdminShortcuts, EventApiSession, RehearsalAccounts, readRehearsalAccounts, resetRehearsal, setPracticeKeys } from "./event-api-helper";
import { openRolePage, signIn } from "./event-ui-helper";

/**
 * The look of the SENTINEL stage screens: formulas written as "$$ " lines are shown as real formulas (nothing of
 * the raw "$$ " is visible), the data lines are one monospaced block, the text is a readable column, and nothing
 * sticks out sideways at 1440 or 390 px. Stages 1, 3, 5, 7 and 8 are photographed in both sizes.
 */
let accounts: RehearsalAccounts;
const shots = path.resolve("..", "docs", "ui-after");
const MISSION_FILE = path.resolve("..", "docs", "ai-room", "sentinel-content.json");
const SIZES = [
  { name: "1440", size: { width: 1440, height: 900 } },
  { name: "390", size: { width: 390, height: 844 } },
];
/** How many display formulas each stage shows (field note and brief together), counted from the content file. */
const FORMULAS: Record<number, number> = { 1: 2, 3: 2, 5: 0, 7: 2, 8: 4 };

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

const card = (page: Page): Locator => page.locator("event-sentinel-activity");
const stage = (page: Page, n: number): Locator => card(page).locator(`[data-stage="${n}"]`);

async function expectNoOverflow(page: Page, what: string) {
  const { scrollWidth, innerWidth } = await page.evaluate(() => ({ scrollWidth: document.documentElement.scrollWidth, innerWidth: window.innerWidth }));
  expect(scrollWidth, `horizontal overflow: ${what}`).toBeLessThanOrEqual(innerWidth + 1);
}

async function expectContained(page: Page, n: number, what: string) {
  const outside = await stage(page, n).evaluate((root) => {
    const edge = root.getBoundingClientRect().right;
    const bad: string[] = [];
    root.querySelectorAll("*").forEach((element) => {
      if (element.closest("svg, .table-wrap, pre")) return;
      const box = element.getBoundingClientRect();
      if (box.width > 0 && box.right > edge + 1) bad.push(`${element.tagName.toLowerCase()}.${String(element.className)}`);
    });
    return bad;
  });
  expect(outside, `parts of stage ${n} stick out of it: ${what}`).toEqual([]);
}

async function writeShot(name: string, image: Buffer) {
  if (process.env.DEVFEST_NO_SCREENSHOTS) return;
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

test("sentinel: formulas are shown as formulas, the text is a readable column and stages 1, 3, 5, 7 and 8 fit 1440 and 390 px", async ({ browser }) => {
  test.setTimeout(240000);
  const team = await openRolePage(browser, "team", SIZES[0].size);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  try {
    await setPracticeKeys(accounts, ["AI"]);
    const manager = await EventApiSession.signIn(accounts.managers.AI, accounts.seedPassword);
    try {
      // The shipped file, with its "$$ " lines, is accepted by the import as it is.
      await manager.send("POST", `/event/rooms/${await control.roomId("AI")}/mission/import`, JSON.parse(fs.readFileSync(MISSION_FILE, "utf8")));
    } finally {
      await manager.dispose();
    }
    await control.startRound(1);
    await control.openRoom("AI");
    const page = team.page;
    await signIn(page, accounts.members[0], accounts.seedPassword);
    await card(page).locator("[data-begin] button").click();

    for (const { name, size } of SIZES) {
      await page.setViewportSize(size);
      for (const n of [1, 3, 5, 7, 8]) {
        await test.step(`stage ${n} at ${name}`, async () => {
          await card(page).locator(`[data-stage-link="${n}"]`).click();
          const s = stage(page, n);
          await expect(s).toBeVisible();

          // Real formulas: the right number of them, serif, with no raw "$$" anywhere on the screen.
          await expect(s.locator("[data-formula]")).toHaveCount(FORMULAS[n]);
          await expect(s).not.toContainText("$$");
          if (FORMULAS[n] > 0) {
            const family = await s.locator("[data-formula]").first().evaluate((el) => getComputedStyle(el).fontFamily);
            expect(family, "a serif maths stack").toMatch(/Cambria Math|Times New Roman|serif/i);
          }

          // The text is a readable column, never wider than about 70 characters, with a 1.6 line height.
          const brief = s.locator("sentinel-brief").last();
          const metrics = await brief.evaluate((el) => {
            const style = getComputedStyle(el);
            const ch = parseFloat(style.fontSize) * 0.55;
            return { width: el.getBoundingClientRect().width, lineHeight: parseFloat(style.lineHeight) / parseFloat(style.fontSize), ch };
          });
          expect(metrics.lineHeight).toBeGreaterThanOrEqual(1.55);
          expect(metrics.width).toBeLessThanOrEqual(metrics.ch * 80);

          // The data lines are one block, not one box per line.
          if (n === 8) await expect(s.locator("pre[data-data-block]")).toHaveCount(1);

          await expectNoOverflow(page, `stage ${n} at ${name}`);
          await expectContained(page, n, name);
          await s.scrollIntoViewIfNeeded();
          await writeShot(`sentinel-polish-${String(n).padStart(2, "0")}-${name}.png`, await s.screenshot());
        });
      }
    }
  } finally {
    await adminApi.dispose();
    await team.page.context().close().catch(() => {});
  }
  expect(team.problems).toEqual([]);
});
