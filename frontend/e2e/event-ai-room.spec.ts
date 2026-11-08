import { test, expect, Locator, Page } from "@playwright/test";
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
import { clickAndConfirm, openRolePage, signIn } from "./event-ui-helper";

let accounts: RehearsalAccounts;
const shots = path.resolve("..", "docs", "ui-after");
const MISSION_FILE = path.resolve("..", "docs", "ai-room", "sentinel-content.json");
const WIDE = { width: 1440, height: 900 };
const NARROW = { width: 390, height: 844 };
/** Teams 01 and 02 are both in group A: the AI room in round 1. */
const PASSING_TEAM = 0;
const FAILING_TEAM = 1;
const PAGE_NUMBER = /\d/;

/** The stages of the mission, in order, with the title the team sees. */
const STAGES = [
  { n: 1, title: "Outlier Hunt" },
  { n: 2, title: "Feature Check" },
  { n: 3, title: "Tune & Deploy" },
  { n: 4, title: "Nearest Vehicle" },
  { n: 5, title: "Camera Accuracy" },
  { n: 6, title: "Group the Frames" },
  { n: 7, title: "Route Planner" },
  { n: 8, title: "Threat Filter" },
  { n: 9, title: "Rule Engine" },
  { n: 10, title: "RAG Fix" },
];

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

function card(page: Page): Locator {
  return page.locator("event-sentinel-activity");
}

/** The stage on screen. */
function stage(page: Page, n: number): Locator {
  return card(page).locator(`[data-stage="${n}"]`);
}

async function openStage(page: Page, n: number) {
  await card(page).locator(`[data-stage-link="${n}"]`).click();
  await expect(stage(page, n)).toBeVisible();
}

async function submitStage(page: Page, n: number, last = false) {
  await clickAndConfirm(page, stage(page, n).locator("[data-submit-stage] button"));
  // The last stage records the team's result, so the whole page turns into the ending.
  if (last) await expect(card(page).locator("[data-sentinel-result]")).toBeVisible();
  else await expect(stage(page, n).locator("[data-stage-saved]")).toBeVisible();
}

/** Nothing sticks out sideways at the current viewport. */
async function expectNoOverflow(page: Page, what: string) {
  const { scrollWidth, innerWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
  }));
  expect(scrollWidth, `horizontal overflow: ${what}`).toBeLessThanOrEqual(innerWidth + 1);
}

/** Nothing inside the stage sticks out past its right edge (tables may scroll inside their own wrapper). */
async function expectContained(page: Page, n: number, what: string) {
  const outside = await stage(page, n).evaluate((root) => {
    const edge = root.getBoundingClientRect().right;
    const bad: string[] = [];
    root.querySelectorAll("*").forEach((element) => {
      if (element.closest("svg, .table-wrap")) return;
      const box = element.getBoundingClientRect();
      if (box.width > 0 && box.right > edge + 1) bad.push(`${element.tagName.toLowerCase()}.${String(element.className)}`);
    });
    return bad;
  });
  expect(outside, `parts of stage ${n} stick out of it: ${what}`).toEqual([]);
}

/** The plot is big enough to work with on a laptop and never taller than the screen. */
async function expectUsablePlot(page: Page, n: number) {
  const box = await stage(page, n).locator("svg.plot").first().boundingBox();
  expect(box, `plot of stage ${n}`).not.toBeNull();
  expect(box!.width).toBeGreaterThanOrEqual(440);
  expect(box!.height).toBeGreaterThanOrEqual(300);
  expect(box!.height).toBeLessThanOrEqual(WIDE.height * 0.62);
  // Opening the stage brings it to the top of the screen: the whole plot and the Submit button are in view at once.
  await expect
    .poll(async () => {
      const plot = await stage(page, n).locator("svg.plot").first().boundingBox();
      const submit = await stage(page, n).locator("[data-submit-stage]").boundingBox();
      return !!plot && !!submit && plot.y >= 0 && plot.y + plot.height <= WIDE.height && submit.y + submit.height <= WIDE.height;
    }, { message: `plot and Submit of stage ${n} within a ${WIDE.height}px laptop screen` })
    .toBe(true);
}

/** The whole SENTINEL card (all of it, however tall), without the page chrome that scrolls around it. */
async function shot(page: Page, name: string) {
  if (process.env.DEVFEST_NO_SCREENSHOTS) return;
  const image = await card(page).screenshot();
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

/** Stage 1: IQR with 1.5 times the IQR. Returns after the run result shows. */
async function playOutlier(page: Page) {
  const s = stage(page, 1);
  await s.getByRole("radio", { name: /^IQR/ }).check();
  await s.getByRole("radio", { name: "1.5 × IQR" }).check();
  await expect(s.locator("[data-run-result]")).toContainText("has not been run yet");
  await s.locator("[data-run]").click();
  await expect(s.locator("[data-run-result]")).toContainText("Flags 3");
}

async function playFeatures(page: Page) {
  const s = stage(page, 2);
  for (const label of ["Entry gate", "Entry time", "Vehicle type", "Previous visits"]) {
    await s.getByRole("group", { name: label, exact: true }).getByRole("radio", { name: "Use" }).check();
  }
  for (const label of ["Plate number", "Exit camera timestamp"]) {
    await s.getByRole("group", { name: label, exact: true }).getByRole("radio", { name: "Drop" }).check();
  }
}

async function playTune(page: Page) {
  const s = stage(page, 3);
  for (const config of ["knn-1", "knn-5", "tree-2", "tree-5"]) {
    await s.locator(`[data-config="${config}"] [data-run]`).click();
    await expect(s.locator(`[data-config="${config}"] [data-validation]`)).toBeVisible();
  }
  await expect(s.locator("[data-runs-left]")).toContainText("Runs left: 0 of 4");
  await expect(s.locator('[data-config="knn-15"] [data-run]')).toBeDisabled();
  await s.getByRole("radio", { name: "Deploy Decision tree, depth 5" }).check();
}

async function playNearest(page: Page) {
  const s = stage(page, 4);
  await expect(s.locator("[data-plot-note]")).toHaveText("Distance is measured in metres on both axes, so the neighbourhood looks like an ellipse.");
  // The unknown vehicle is the last thing drawn (on top): a hollow ~12 px diamond, 2.5 px dark outline, thin white halo.
  const unknown = await s.locator("svg.plot").evaluate((svg) => {
    const group = svg.querySelector("[data-unknown-vehicle]")!;
    const marks = [...svg.querySelectorAll("circle.pt, rect.pt, path.pt")];
    const ink = group.querySelector("path.unknown--ink")!;
    const halo = group.querySelector("path.unknown--halo")!;
    const box = (ink as SVGGraphicsElement).getBBox();
    return {
      lastAndAbove: marks.length === 12 && marks.every((mark) => !!(mark.compareDocumentPosition(group) & Node.DOCUMENT_POSITION_FOLLOWING)) && svg.lastElementChild === group,
      haloBelowInk: !!(halo.compareDocumentPosition(ink) & Node.DOCUMENT_POSITION_FOLLOWING),
      fill: getComputedStyle(ink).fill,
      inkWidth: getComputedStyle(ink).strokeWidth,
      haloStroke: getComputedStyle(halo).stroke,
      size: Math.round(box.width),
    };
  });
  expect(unknown).toEqual({ lastAndAbove: true, haloBelowInk: true, fill: "none", inkWidth: "2.5px", haloStroke: "rgb(255, 255, 255)", size: 12 });
  await s.locator("#near-k").fill("1");
  await expect(s.locator("[data-votes]")).toHaveText(/Car × 1/);
  await s.locator("#near-k").fill("5");
  await expect(s.locator("[data-votes]")).toContainText("Van × 4");
  await s.getByRole("group", { name: "Class when k = 1" }).getByRole("radio", { name: "Car" }).check();
  await s.getByRole("group", { name: "Class when k = 5" }).getByRole("radio", { name: "Van" }).check();
  await s.getByRole("group", { name: "Which setting is more reliable?" }).getByRole("radio", { name: "k = 5" }).check();
}

async function playCamera(page: Page) {
  const s = stage(page, 5);
  await s.getByLabel("TP", { exact: true }).fill("6");
  await s.getByLabel("FN", { exact: true }).fill("4");
  await s.getByLabel("FP", { exact: true }).fill("2");
  await s.getByLabel("TN", { exact: true }).fill("8");
  await s.locator("#cam-precision").fill("0.75");
  await s.locator("#cam-recall").fill("0,6");
  await s.getByRole("radio", { name: "Recall" }).check();
}

async function playGroups(page: Page) {
  const s = stage(page, 6);
  await expect(s.locator("[data-update]")).toBeDisabled();
  // Each "Cluster" label rides with its centroid: its offset from the star stays the same after every Update.
  const offsets = () =>
    s.locator("[data-centre]").evaluateAll((groups) =>
      groups.map((g) => {
        const star = g.querySelector("path")!.getBoundingClientRect();
        const label = g.querySelector("[data-centre-label]")!.getBoundingClientRect();
        return { dx: Math.round(label.left - star.left), dy: Math.round(label.top - star.top), x: Math.round(star.left), y: Math.round(star.top) };
      }),
    );
  const before = await offsets();
  expect(before).toHaveLength(2);
  for (let round = 0; round < 3; round++) {
    await s.locator("[data-assign]").click();
    await expect(s.locator("[data-update]")).toBeEnabled();
    await s.locator("[data-update]").click();
    await expect(s.locator("[data-update]")).toBeDisabled();
  }
  await expect(s.locator("[data-hint]")).toContainText("Nothing moved");
  await page.waitForTimeout(1000); // the centroids finish sliding
  const after = await offsets();
  expect(after.some((a, i) => Math.abs(a.x - before[i].x) + Math.abs(a.y - before[i].y) > 20), "the centroids moved").toBe(true);
  after.forEach((a, i) => expect([a.dx, a.dy], `label ${i} follows its centroid`).toEqual([before[i].dx, before[i].dy]));
  await expect(s.locator("[data-readout]")).toContainText("6 frames · centre (7.50, 6.50)");
  await s.getByLabel("Frames", { exact: true }).fill("6");
  await s.getByLabel("Centroid x").fill("7.5");
  await s.getByLabel("Centroid y").fill("6.5");
}

async function playRoute(page: Page) {
  const s = stage(page, 7);
  await expect(s.locator("[data-tie-break]")).toContainText("comes first alphabetically");
  for (const place of ["lib", "lab", "dor", "adm", "gar", "g3"]) {
    await s.locator(`[data-frontier] [data-place="${place}"]`).click();
  }
  await expect(s.locator("[data-route]")).toContainText("Library → Lab → Admin → Garage → Gate 3 · cost 15");
}

async function playThreat(page: Page) {
  const s = stage(page, 8);
  await s.getByLabel("Threat value").fill("0.00012");
  await s.getByLabel("Normal value").fill("1.0125e-3");
  // One shared scale, no minimum width: every bar is exactly proportional to its value.
  const bars = await s.locator("[data-bar]").evaluateAll((els) =>
    els.map((el) => ({ value: Number(el.getAttribute("data-value")), width: el.getBoundingClientRect().width })),
  );
  expect(bars).toHaveLength(12);
  const scale = bars[0].width / bars[0].value;
  for (const bar of bars) expect(bar.width / bar.value, `bar ${bar.value}`).toBeCloseTo(scale, 0);
  const widthOf = (value: number) => bars.find((bar) => bar.value === value)!.width;
  const distinct = [0.02, 0.05, 0.15, 0.2].map(widthOf);
  expect(distinct).toEqual([...distinct].sort((a, b) => a - b));
  expect(new Set(distinct.map((w) => Math.round(w))).size).toBe(4);
  expect(widthOf(0.02)).toBeLessThan(widthOf(0.05) * 0.5);
  await s.getByRole("radio", { name: "Normal" }).check();
}

async function playRules(page: Page) {
  const s = stage(page, 9);
  await s.locator('[data-rule="R3"]').click();
  await expect(s.locator("[data-rules]")).toContainText("Conditions not met");
  for (const rule of ["R1", "R2", "R3", "R4"]) await s.locator(`[data-rule="${rule}"]`).click();
  await expect(s.locator("[data-facts]")).toContainText("Someone on the inside was involved");
  // A conclusion that is not among the known facts is questioned before it is locked; nothing else hints at it.
  await s.getByRole("radio", { name: "There is a cover-up" }).check();
  await expect(s.locator("[data-submit-stage] button")).toBeEnabled();
  await s.locator("[data-submit-stage] button").click();
  const warning = page.getByRole("dialog");
  await expect(warning).toContainText("This conclusion is not in what you know. Submit anyway?");
  await warning.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(warning).toBeHidden();
  await s.getByRole("radio", { name: "Someone on the inside was involved" }).check();
  await s.locator("[data-submit-stage] button").click();
  await expect(page.getByRole("dialog")).toContainText("Submit stage?");
  await expect(page.getByRole("dialog")).not.toContainText("not in what you know");
  await page.getByRole("dialog").getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeHidden();
}

async function ask(page: Page, size: string, k: number) {
  const s = stage(page, 10);
  await s.getByRole("group", { name: "Chunk size" }).getByRole("radio", { name: size }).check();
  await s.getByRole("group", { name: /^Top-k/ }).getByRole("radio", { name: `Top-${k}` }).check();
  await s.locator("[data-ask]").click();
}

async function playRag(page: Page) {
  const s = stage(page, 10);
  await ask(page, "Small", 1);
  await expect(s.locator('[data-run="small-1"]')).toContainText("NOOR:");
  await ask(page, "Large", 1);
  await expect(s.locator('[data-run="large-1"]')).toContainText("Library reading room");
  await ask(page, "Small", 3);
  await expect(s.locator("[data-runs-left]")).toContainText("Tries left: 0 of 3");
  await s.getByRole("radio", { name: "Large chunks · top-1" }).check();
}

const PLAYERS: Record<number, (page: Page) => Promise<void>> = {
  1: playOutlier,
  2: playFeatures,
  3: playTune,
  4: playNearest,
  5: playCamera,
  6: playGroups,
  7: playRoute,
  8: playThreat,
  9: playRules,
  10: playRag,
};

/** Imports the shipped mission file the way the room manager's import does (the page for it comes later). */
async function importMission(roomId: number) {
  const manager = await EventApiSession.signIn(accounts.managers.AI, accounts.seedPassword);
  try {
    await manager.send("POST", `/event/rooms/${roomId}/mission/import`, JSON.parse(fs.readFileSync(MISSION_FILE, "utf8")));
  } finally {
    await manager.dispose();
  }
}

test("sentinel: one team repairs it and sees the clue, another does not and sees no number", async ({ browser }) => {
  test.setTimeout(420000);
  const passing = await openRolePage(browser, "team", WIDE);
  const failing = await openRolePage(browser, "team-2", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  try {
    await setPracticeKeys(accounts, ["AI", "PROBLEM_SOLVING", "LINUX", "HARDWARE"]);
    const aiRoom = await control.roomId("AI");
    await importMission(aiRoom);
    await control.setRound(1);
    await control.openRoom("AI");
    const page = passing.page;
    await signIn(page, accounts.members[PASSING_TEAM], accounts.seedPassword);

    await test.step("before the timer starts the team sees the story and waits", async () => {
      await expect(card(page).locator("[data-sentinel-start]")).toContainText("SENTINEL OFFLINE. Rami was investigating this AI");
      await expect(card(page).locator("[data-sentinel-waiting]")).toHaveText("The repairs open when the timer starts.");
      await expect(page.getByRole("textbox", { name: "Clue answer", exact: true })).toHaveCount(0);
      await shot(page, "sentinel-waiting-1440.png");
      await control.startTimer();
      await expect(card(page).locator("[data-begin]")).toBeVisible();
    });

    await test.step("the start screen, then three mission panels with stage status", async () => {
      await shot(page, "sentinel-start-1440.png");
      await card(page).locator("[data-begin] button").click();
      for (const mission of ["predict", "see", "noor"]) await expect(card(page).locator(`[data-mission="${mission}"]`)).toBeVisible();
      await expect(card(page).locator("[data-stage-link]")).toHaveCount(10);
      await expect(card(page).locator("ui-countdown")).toContainText("left");
      await expectNoOverflow(page, "mission panels at 1440");
    });

    for (const { n, title } of STAGES) {
      await test.step(`stage ${n} ${title}: played in the browser, shown, submitted`, async () => {
        await openStage(page, n);
        await expect(stage(page, n).locator("[data-field-note]")).toContainText("Rami's notes");
        // Right after opening, before any interaction scrolls the page: the plot and Submit are in view together.
        if ([1, 4, 6, 7].includes(n)) await expectUsablePlot(page, n);
        await PLAYERS[n](page);
        await expectNoOverflow(page, `stage ${n} at 1440`);
        await expectContained(page, n, "1440");
        await expect(stage(page, n).locator("[data-submit-stage] button")).toBeEnabled();
        if (n === 6) await page.waitForTimeout(1000); // the centroids finish sliding
        await shot(page, `sentinel-stage-${String(n).padStart(2, "0")}-1440.png`);
        await submitStage(page, n, n === 10);
        if (n < 10) await expect(card(page).locator(`[data-stage-link="${n}"] [data-saved-mark]`)).toContainText("Saved");
      });
    }

    await test.step("the last stage records the result: SENTINEL restored, the clue behind the eye", async () => {
      const result = card(page).locator('[data-sentinel-result="unlocked"]');
      await expect(result).toBeVisible();
      await expect(result).toContainText("SENTINEL RESTORED. All systems are back online. Recovered record:");
      await expect(result.locator("[data-clue-value]")).not.toContainText(PRACTICE_KEYS.AI);
      await result.getByRole("button", { name: "Show clue", exact: true }).click();
      await expect(result.locator("[data-clue-value]")).toHaveText(PRACTICE_KEYS.AI);
      const text = (await card(page).innerText()).toLowerCase();
      for (const word of ["points", "score", "total", "correct", "pass"]) expect(text).not.toContain(word);
      await expect(page.locator('[data-evidence="AI"] [data-quiz-outcome]')).toContainText("Clue unlocked");
      await expect(page.locator('[data-room="AI"]')).toContainText("Room complete");
      await expect(page.locator('[data-room="AI"]')).not.toContainText("Submitted");
      await expectNoOverflow(page, "restored ending at 1440");
      await result.scrollIntoViewIfNeeded();
      await shot(page, "sentinel-restored-1440.png");
      await page.setViewportSize(NARROW);
      await expectNoOverflow(page, "restored ending at 390");
      await result.scrollIntoViewIfNeeded();
      await shot(page, "sentinel-restored-390.png");
      await page.setViewportSize(WIDE);
    });

    await test.step("Team 02 saves one stage; at the round's end it did not restore SENTINEL and sees no number", async () => {
      const other = failing.page;
      await signIn(other, accounts.members[FAILING_TEAM], accounts.seedPassword);
      await card(other).locator("[data-begin] button").click();
      await openStage(other, 1);
      await playOutlier(other);
      await submitStage(other, 1);
      await adminApi.send("POST", "/event/admin/round/timer/end");
      const result = card(other).locator('[data-sentinel-result="not-reached"]');
      await expect(result).toBeVisible();
      await expect(result).toContainText("SENTINEL UNSTABLE. Some systems could not be restored. Your team did not reach the required score.");
      await expect(card(other).locator("[data-clue-value]")).toHaveCount(0);
      expect(await card(other).innerText(), "no number anywhere on the ending").not.toMatch(PAGE_NUMBER);
      expect(await card(other).innerHTML()).not.toContain(PRACTICE_KEYS.AI);
      await expect(other.locator('[data-room="AI"]')).toContainText("Room complete");
      await other.evaluate(() => window.scrollTo(0, 0));
      await expectNoOverflow(other, "unstable ending at 1440");
      await shot(other, "sentinel-unstable-1440.png");
      await other.setViewportSize(NARROW);
      await expectNoOverflow(other, "unstable ending at 390");
      await shot(other, "sentinel-unstable-390.png");
    });
  } finally {
    await adminApi.dispose();
    for (const role of [passing, failing]) await role.page.context().close().catch(() => {});
  }
  expect([...passing.problems, ...failing.problems]).toEqual([]);
});

test("sentinel: a refresh or a new sign-in resumes mid-way and the experiment budgets persist", async ({ browser }) => {
  test.setTimeout(300000);
  const team = await openRolePage(browser, "team", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  try {
    await setPracticeKeys(accounts, ["AI"]);
    await importMission(await control.roomId("AI"));
    await control.startRound(1);
    await control.openRoom("AI");
    const page = team.page;
    await signIn(page, accounts.members[PASSING_TEAM], accounts.seedPassword);
    await card(page).locator("[data-begin] button").click();

    await test.step("every stage fits a phone", async () => {
      await page.setViewportSize(NARROW);
      for (const { n } of STAGES) {
        await openStage(page, n);
        await expectNoOverflow(page, `stage ${n} at 390`);
        await expectContained(page, n, "390");
      }
      for (const n of [4, 6, 7]) {
        await openStage(page, n);
        await stage(page, n).scrollIntoViewIfNeeded();
        await shot(page, `sentinel-stage-${String(n).padStart(2, "0")}-390.png`);
      }
      await page.setViewportSize(WIDE);
    });

    await test.step("stage 1 is saved, stage 3 and stage 10 use part of their budgets", async () => {
      await openStage(page, 1);
      await playOutlier(page);
      await submitStage(page, 1);
      await openStage(page, 3);
      for (const config of ["knn-1", "knn-5", "tree-2"]) await stage(page, 3).locator(`[data-config="${config}"] [data-run]`).click();
      await expect(stage(page, 3).locator("[data-runs-left]")).toContainText("Runs left: 1 of 4");
      await openStage(page, 10);
      await ask(page, "Small", 1);
      await ask(page, "Large", 3);
      await expect(stage(page, 10).locator("[data-runs-left]")).toContainText("Tries left: 1 of 3");
    });

    const expectResumed = async (what: string) => {
      await expect(card(page).locator("[data-sentinel-start]"), `${what}: no start screen again`).toHaveCount(0);
      await expect(card(page).locator('[data-stage-link="1"] [data-saved-mark]')).toContainText("Saved");
      await openStage(page, 1);
      await expect(stage(page, 1).locator("[data-stage-saved]")).toBeVisible();
      await expect(stage(page, 1).locator("svg")).toHaveCount(0);
      await openStage(page, 3);
      await expect(stage(page, 3).locator("[data-runs-left]")).toContainText("Runs left: 1 of 4");
      for (const config of ["knn-1", "knn-5", "tree-2"]) await expect(stage(page, 3).locator(`[data-config="${config}"] [data-validation]`)).toBeVisible();
      await expect(stage(page, 3).locator('[data-config="tree-5"] [data-validation]')).toHaveCount(0);
      await openStage(page, 10);
      await expect(stage(page, 10).locator("[data-runs-left]")).toContainText("Tries left: 1 of 3");
      await expect(stage(page, 10).locator("[data-run]")).toHaveCount(2);
    };

    await test.step("a page refresh resumes with the saved stage, the runs and the budgets", async () => {
      await page.reload();
      await expectResumed("after a refresh");
    });

    await test.step("the last run is spent and nothing more can run", async () => {
      await openStage(page, 3);
      await stage(page, 3).locator('[data-config="tree-5"] [data-run]').click();
      await expect(stage(page, 3).locator("[data-runs-left]")).toContainText("Runs left: 0 of 4");
      await expect(stage(page, 3).locator('[data-config="knn-15"] [data-run]')).toBeDisabled();
      await expect(stage(page, 3).locator('[data-config="knn-1"] [data-run]')).toHaveText("Done");
      await page.reload();
      await openStage(page, 3);
      await expect(stage(page, 3).locator("[data-runs-left]")).toContainText("Runs left: 0 of 4");
      await expect(stage(page, 3).locator('[data-config="tree-20"] [data-run]')).toBeDisabled();
    });

    await test.step("a paused round freezes the input", async () => {
      await adminApi.send("POST", "/event/admin/round/timer/pause");
      await expect(card(page).locator("ui-status-chip", { hasText: "Paused" })).toBeVisible();
      await openStage(page, 9);
      await expect(stage(page, 9).locator('[data-rule="R1"]')).toBeDisabled();
      await expect(stage(page, 9).locator("[data-submit-stage] button")).toBeDisabled();
      await expect(stage(page, 9)).toContainText("Input is frozen right now.");
      await adminApi.send("POST", "/event/admin/round/timer/resume");
      await expect(stage(page, 9).locator('[data-rule="R1"]')).toBeEnabled();
    });
    await test.step("a new sign-in in a fresh browser resumes the same way", async () => {
      // Team accounts keep one session: the first browser is closed before the same team signs in again.
      await page.context().close();
      const again = await openRolePage(browser, "team-again", WIDE);
      try {
        await signIn(again.page, accounts.members[PASSING_TEAM], accounts.seedPassword);
        await expect(card(again.page).locator("[data-sentinel-start]")).toHaveCount(0);
        await expect(card(again.page).locator('[data-stage-link="1"] [data-saved-mark]')).toContainText("Saved");
        await openStage(again.page, 3);
        await expect(stage(again.page, 3).locator("[data-runs-left]")).toContainText("Runs left: 0 of 4");
        await openStage(again.page, 10);
        await expect(stage(again.page, 10).locator("[data-runs-left]")).toContainText("Tries left: 1 of 3");
        expect(again.problems).toEqual([]);
      } finally {
        await again.page.context().close().catch(() => {});
      }
    });
  } finally {
    await adminApi.dispose();
    await team.page.context().close().catch(() => {});
  }
  expect(team.problems).toEqual([]);
});

test("sentinel: a room without mission content blocks the timer until the admin presses Start anyway", async ({ browser }) => {
  test.setTimeout(180000);
  const admin = await openRolePage(browser, "admin", WIDE);
  const team = await openRolePage(browser, "team", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  try {
    await setPracticeKeys(accounts, ["AI", "PROBLEM_SOLVING", "LINUX", "HARDWARE"]);
    await control.setRound(1);
    await control.openRoom("AI");

    await test.step("the admin sees the blocking alert and must confirm Start anyway", async () => {
      const page = admin.page;
      await signIn(page, accounts.adminEmail, accounts.adminPassword);
      const blocking = page.locator('[data-alert="MISSION_WITHOUT_CONTENT"]');
      await expect(blocking).toContainText("Room AI runs the AI mission but has no mission content.");
      await expect(blocking).toContainText("Blocks the timer");
      await page.getByRole("button", { name: "Start timer", exact: true }).click();
      const dialog = page.getByRole("dialog");
      await expect(dialog.getByRole("heading", { name: "The AI mission is not ready. Start anyway?", exact: true })).toBeVisible();
      await dialog.getByRole("button", { name: "Start anyway", exact: true }).click();
      await expect(page.getByText("Timer started.", { exact: true })).toBeVisible();
    });

    await test.step("the AI room's team sees that the mission is not ready", async () => {
      const page = team.page;
      await signIn(page, accounts.members[PASSING_TEAM], accounts.seedPassword);
      await expect(card(page).locator("[data-sentinel-not-ready]")).toHaveText("The mission is not ready yet. Wait for the organizer.");
    });
  } finally {
    await adminApi.dispose();
    for (const role of [admin, team]) await role.page.context().close().catch(() => {});
  }
  expect([...admin.problems, ...team.problems]).toEqual([]);
});
