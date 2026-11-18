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
  { n: 3, title: "Neuron Check" },
  { n: 4, title: "Nearest Vehicle" },
  { n: 5, title: "Camera Accuracy" },
  { n: 6, title: "Patrol Evolution" },
  { n: 7, title: "Route Planner" },
  { n: 8, title: "Best Split" },
  { n: 9, title: "Patrol Game" },
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
  // The new order: entry-gate, plate-number, entry-time, exit-camera-time, vehicle-type, previous-visits.
  await expect(s.locator("[data-features] fieldset legend")).toHaveText([
    "Entry gate",
    "Plate number",
    "Entry time",
    "Exit camera timestamp",
    "Vehicle type",
    "Previous visits",
  ]);
  for (const label of ["Entry gate", "Entry time", "Vehicle type", "Previous visits"]) {
    await s.getByRole("group", { name: label, exact: true }).getByRole("radio", { name: "Use" }).check();
  }
  for (const label of ["Plate number", "Exit camera timestamp"]) {
    await s.getByRole("group", { name: label, exact: true }).getByRole("radio", { name: "Drop" }).check();
  }
}

/** The text of the option that is the right answer of the multiple choice stage (the options are shuffled per team). */
const RIGHT_OPTION = "0";
/** What a team types for each typed stage; the server accepts several forms of each. */
const TYPED_ANSWER: Record<number, string> = { 6: "101 001", 8: ".19", 9: "4" };
/** Words that would give an answer away: no figure's text alternative may hold one. */
const GIVEAWAYS = ["101001", "0.19", "-1.5", "ReLU(z)", "entropy", "= 4"];

/** The ids of the choice stage's options in the order this team sees them. */
async function optionOrder(page: Page, n: number): Promise<string[]> {
  return stage(page, n).locator("[data-option]").evaluateAll((cards) => cards.map((card) => card.getAttribute("data-option") ?? ""));
}

/** Stage 3: the right answer is picked by clicking its card. */
async function playChoice(page: Page, n: number) {
  const s = stage(page, n);
  await expect(s.locator("[data-option]")).toHaveCount(4);
  await expect(s.locator("[data-submit-stage] button")).toBeDisabled();
  await s.getByRole("radio", { name: RIGHT_OPTION, exact: true }).click();
  await expect(s.getByRole("radio", { name: RIGHT_OPTION, exact: true })).toHaveAttribute("aria-checked", "true");
}

/** The crossover builder: its tiles and slots, and what the child shows now. */
function tile(page: Page, row: "A" | "B", position: number): Locator {
  return stage(page, 6).locator(`[data-tile="${row}-${position}"]`);
}
function slot(page: Page, position: number): Locator {
  return stage(page, 6).locator(`[data-slot="${position}"]`);
}
async function child(page: Page): Promise<string> {
  return (await stage(page, 6).locator("[data-slot]").evaluateAll((slots) => slots.map((el) => (el.textContent ?? "").replace("★", "").trim()))).join("");
}

/**
 * Stage 6 never shows how to solve it: no cut line or label, no flip marker, no tint on one part of a parent, every
 * tile and slot drawn the same way, and nothing in the labels that points at the cut or the mutated position.
 */
async function expectNeutral(page: Page) {
  const s = stage(page, 6);
  await expect(s.locator(".cutline, [data-cut-label], [data-flipmark]")).toHaveCount(0);
  await expect(s.locator("[data-crossover]")).not.toContainText(/cut after/i);
  const looks = async (locator: Locator) => locator.evaluate((el) => {
    const style = getComputedStyle(el);
    return [style.backgroundColor, style.borderTopColor, style.borderTopWidth, style.outlineStyle].join("|");
  });
  const parents = [await looks(tile(page, "A", 1)), await looks(tile(page, "A", 6)), await looks(tile(page, "B", 1)), await looks(tile(page, "B", 6)), await looks(tile(page, "B", 4)), await looks(tile(page, "A", 3))];
  expect(new Set(parents).size, `every parent tile looks the same: ${parents.join(" ; ")}`).toBe(1);
  const labels = await s.locator("[data-tile], [data-slot]").evaluateAll((all) => all.map((el) => el.getAttribute("aria-label") ?? ""));
  for (const label of labels) expect(label, label).not.toMatch(/cut|mutat|here|flip marker/i);
}

/** A shot of one stage (the builder in a state), written the way the other shots are. */
async function shotStage(page: Page, n: number, name: string) {
  if (process.env.DEVFEST_NO_SCREENSHOTS) return;
  await stage(page, n).scrollIntoViewIfNeeded();
  const image = await stage(page, n).screenshot();
  for (let attempt = 1; ; attempt++) {
    try {
      fs.writeFileSync(path.join(shots, name), image);
      return;
    } catch (error) {
      if (attempt >= 10) throw error;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
  }
}

/**
 * Stage 6: builds the child from the parents (Parent A 1 0 1 1 0 0, Parent B 0 1 1 0 1 1, cut after 3): the first three
 * bits by dragging, the last three by tapping, then the mutation of position 5, undone once and done again.
 */
async function playCrossover(page: Page) {
  const s = stage(page, 6);
  await expect(s.locator("[data-figure]"), "the builder replaces the static figure").toHaveCount(0);
  await expect(s.locator("[data-crossover]")).toBeVisible();
  await expectNeutral(page);
  await expect(s.locator("[data-answer]"), "no text box").toHaveCount(0);
  await expect(s.locator("[data-submit-stage] button")).toBeDisabled();
  expect(await child(page)).toBe("??????");
  // Crossover by dragging: the parent tile stays where it is.
  for (const position of [1, 2, 3]) await tile(page, "A", position).dragTo(slot(page, position));
  expect(await child(page)).toBe("101???");
  await expect(tile(page, "A", 1)).toBeVisible();
  // Crossover by tapping: the tile highlights, then the slot takes its bit.
  for (const position of [4, 5, 6]) {
    await tile(page, "B", position).click();
    await expect(tile(page, "B", position)).toHaveAttribute("aria-pressed", "true");
    await slot(page, position).click();
    await expect(tile(page, "B", position)).toHaveAttribute("aria-pressed", "false");
  }
  expect(await child(page)).toBe("101011");
  await expect(s.locator("[data-status]")).toHaveText("Ready to submit.");
  // Mutation of position 5: the bit flips and the slot gets a star; Undo takes it back; doing it again flips it.
  await s.locator("[data-mutate]").click();
  await expect(s.locator("[data-status]")).toHaveText("Mutation mode: tap one child bit to flip it.");
  await slot(page, 5).click();
  expect(await child(page)).toBe("101001");
  await expect(s.locator("[data-star]")).toHaveCount(1);
  await s.locator("[data-undo]").click();
  expect(await child(page)).toBe("101011");
  await expect(s.locator("[data-star]")).toHaveCount(0);
  await slot(page, 5).click();
  expect(await child(page)).toBe("101001");
  await expect(slot(page, 5).locator("[data-star]")).toBeVisible();
  await s.locator("[data-mutate]").click();
  await expect(s.locator("[data-status]")).toHaveText("Ready to submit.");
  await expect(s.locator("[data-submit-stage] button")).toBeEnabled();
}

/** Stages 6, 8 and 9: the answer is typed. */
async function playInput(page: Page, n: number) {
  const s = stage(page, n);
  await expect(s.locator("[data-submit-stage] button")).toBeDisabled();
  await s.locator("[data-answer]").fill(TYPED_ANSWER[n]);
  await expect(s.locator("[data-submit-stage] button")).toBeEnabled();
}

/** The figure of a stage: above the brief, scaled to the width, with a text alternative that gives nothing away; shot. */
async function expectFigure(page: Page, n: number, label: string) {
  const s = stage(page, n);
  const figure = s.locator("[data-figure]");
  await expect(figure, `stage ${n} has its figure`).toBeVisible();
  const svg = figure.locator("svg");
  const alt = (await svg.getAttribute("aria-label")) ?? "";
  expect(await svg.getAttribute("role")).toBe("img");
  expect(alt.length, `stage ${n} text alternative`).toBeGreaterThan(30);
  for (const word of GIVEAWAYS) expect(alt, `stage ${n} alternative gives away ${word}`).not.toContain(word);
  expect(((await figure.locator("figcaption").innerText()) ?? "").length).toBeGreaterThan(20);
  // The layout settles after a resize or a stage change: wait for it before comparing positions.
  await expect
    .poll(
      async () => {
        const figureBox = (await figure.boundingBox())!;
        const briefBox = (await s.locator("[data-choice-brief], [data-input-brief]").boundingBox())!;
        return briefBox.y - (figureBox.y + figureBox.height);
      },
      { message: `stage ${n} figure is above the brief at ${label}` },
    )
    .toBeGreaterThanOrEqual(-1);
  const figureBox = (await figure.boundingBox())!;
  const stageBox = (await s.boundingBox())!;
  expect(figureBox.x + figureBox.width, `stage ${n} figure fits its stage at ${label}`).toBeLessThanOrEqual(stageBox.x + stageBox.width + 1);
  const svgBox = (await svg.boundingBox())!;
  if (label === "390") expect(svgBox.width, `stage ${n} figure is readable at 390`).toBeGreaterThan(250);
  if (process.env.DEVFEST_NO_SCREENSHOTS) return;
  const image = await figure.screenshot();
  const file = path.join(shots, `sentinel-figure-${String(n).padStart(2, "0")}-${label}.png`);
  for (let attempt = 1; ; attempt++) {
    try {
      fs.writeFileSync(file, image);
      return;
    } catch (error) {
      if (attempt >= 10) throw error;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
  }
}

/** Stage 3 locked: one answer only, asked first, then "Saved" and nothing about being right. */
async function lockChoice(page: Page) {
  await openStage(page, 3);
  await stage(page, 3).getByRole("radio", { name: "-1.5", exact: true }).click();
  await stage(page, 3).locator("[data-submit-stage] button").click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("One answer only. Submit?");
  await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(stage(page, 3).locator("[data-stage-saved]")).toHaveCount(0);
  await submitStage(page, 3);
  await expect(stage(page, 3).locator("[data-stage-saved]")).toContainText("Saved");
  await expect(stage(page, 3).locator("[data-option]")).toHaveCount(0);
  expect(await stage(page, 3).innerText(), "no sign of whether it was right").not.toMatch(/correct|wrong|right/i);
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

async function playRoute(page: Page) {
  const s = stage(page, 7);
  await expect(s.locator("[data-tie-break]")).toContainText("comes first alphabetically");
  for (const place of ["lib", "lab", "dor", "adm", "gar", "g3"]) {
    await s.locator(`[data-frontier] [data-place="${place}"]`).click();
  }
  await expect(s.locator("[data-route]")).toContainText("Library → Lab → Admin → Garage → Gate 3 · cost 15");
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
  await expect(s.locator('[data-run="small-1"]')).toContainText("ECHO:");
  await ask(page, "Large", 1);
  await expect(s.locator('[data-run="large-1"]')).toContainText("Cybersecurity office");
  await ask(page, "Small", 3);
  await expect(s.locator("[data-runs-left]")).toContainText("Tries left: 0 of 3");
  await s.getByRole("radio", { name: "Large chunks · top-1" }).check();
}

const PLAYERS: Record<number, (page: Page) => Promise<void>> = {
  1: playOutlier,
  2: playFeatures,
  3: (page) => playChoice(page, 3),
  4: playNearest,
  5: playCamera,
  6: playCrossover,
  7: playRoute,
  8: (page) => playInput(page, 8),
  9: (page) => playInput(page, 9),
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
      await expect(card(page).locator("[data-sentinel-start]")).toContainText("SENTINEL OFFLINE. SENTINEL is the AI security system of NOVA Technologies.");
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
      await expect(card(page).locator('[data-mission="noor"] h3')).toContainText("ECHO");
      await expect(card(page).locator('[data-mission="noor"] h3')).not.toContainText("NOOR");
      await expect(card(page).locator("ui-countdown")).toContainText("left");
      await expectNoOverflow(page, "mission panels at 1440");
    });

    for (const { n, title } of STAGES) {
      await test.step(`stage ${n} ${title}: played in the browser, shown, submitted`, async () => {
        await openStage(page, n);
        await expect(stage(page, n).locator("[data-field-note]")).toContainText("Noor's notes");
        // Right after opening, before any interaction scrolls the page: the plot and Submit are in view together.
        if ([1, 4, 7].includes(n)) await expectUsablePlot(page, n);
        await PLAYERS[n](page);
        if ([3, 8, 9].includes(n)) await expectFigure(page, n, "1440");
        await expectNoOverflow(page, `stage ${n} at 1440`);
        await expectContained(page, n, "1440");
        await expect(stage(page, n).locator("[data-submit-stage] button")).toBeEnabled();
        await shot(page, `sentinel-stage-${String(n).padStart(2, "0")}-1440.png`);
        // The builder sends the same 6-bit string the text box did, through the same answer call.
        const sent = n === 6 ? page.waitForRequest((request) => request.method() === "POST" && request.url().includes("/stages/6/submit")) : null;
        await submitStage(page, n, n === 10);
        if (sent) {
          expect((await sent).postDataJSON()).toEqual({ answer: "101001" });
          await expect(stage(page, 6).locator("[data-submitted-child]")).toContainText("101001");
        }
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
      for (const n of [3, 8, 9]) {
        await openStage(page, n);
        await expectFigure(page, n, "390");
      }
      // The answer cards stand one under the other on a phone.
      await openStage(page, 3);
      const narrow = await stage(page, 3).locator("[role=radio]").evaluateAll((cards) => cards.map((card) => Math.round(card.getBoundingClientRect().x)));
      expect(new Set(narrow).size, "one column at 390").toBe(1);
      await page.setViewportSize(WIDE);
    });

    let order3: string[] = [];
    await test.step("stage 1 is saved, stage 10 uses part of its budget, and stage 3 shows its cards", async () => {
      await openStage(page, 1);
      await playOutlier(page);
      await submitStage(page, 1);
      await openStage(page, 10);
      await ask(page, "Small", 1);
      await ask(page, "Large", 3);
      await expect(stage(page, 10).locator("[data-runs-left]")).toContainText("Tries left: 1 of 3");
      await openStage(page, 3);
      order3 = await optionOrder(page, 3);
      expect([...order3].sort()).toEqual(["a", "b", "c", "d"]);
      // Lines that start with four spaces are shown monospaced; no hint of the answer is on screen.
      await openStage(page, 6);
      await expect(stage(page, 6).locator("sentinel-brief pre").first()).toContainText("Parent A:  1 0 1 | 1 0 0");
      await openStage(page, 9);
      await expect(stage(page, 9).locator("sentinel-brief pre").first()).toContainText("MAX");
      expect(await stage(page, 9).innerHTML()).not.toContain("correct");
    });

    await test.step("the answer cards: two columns, letter badges, keyboard and a check mark", async () => {
      await openStage(page, 3);
      const cards = stage(page, 3).locator("[role=radio]");
      await expect(stage(page, 3).locator("[role=radiogroup]")).toHaveAttribute("aria-label", "Choose one answer");
      await expect(cards).toHaveCount(4);
      await expect(stage(page, 3).locator(".card__badge")).toHaveText(["A", "B", "C", "D"]);
      const boxes = await cards.evaluateAll((all) => all.map((card) => ({ x: Math.round(card.getBoundingClientRect().x), y: Math.round(card.getBoundingClientRect().y) })));
      expect(boxes[0].y, "two columns: the first two cards share a row").toBe(boxes[1].y);
      expect(boxes[2].y, "the third card starts the second row").toBeGreaterThan(boxes[0].y);
      expect(boxes[0].x).toBe(boxes[2].x);
      await expect(stage(page, 3).locator("[data-submit-stage] button")).toBeDisabled();
      for (let i = 0; i < 4; i++) await expect(cards.nth(i)).toHaveAttribute("aria-checked", "false");
      await expect(stage(page, 3).locator("[role=radio][tabindex='0']")).toHaveCount(1);
      // Arrow keys move and choose; the focus follows; the arrows wrap around.
      await cards.nth(0).focus();
      await page.keyboard.press("ArrowRight");
      await expect(cards.nth(1)).toHaveAttribute("aria-checked", "true");
      await expect(cards.nth(1)).toBeFocused();
      await page.keyboard.press("ArrowDown");
      await expect(cards.nth(2)).toHaveAttribute("aria-checked", "true");
      await page.keyboard.press("ArrowUp");
      await page.keyboard.press("ArrowLeft");
      await expect(cards.nth(0)).toHaveAttribute("aria-checked", "true");
      await page.keyboard.press("ArrowLeft");
      await expect(cards.nth(3)).toHaveAttribute("aria-checked", "true");
      // Space and Enter choose the focused card; a click chooses too; one check mark, one tab stop.
      await cards.nth(1).focus();
      await page.keyboard.press("Space");
      await expect(cards.nth(1)).toHaveAttribute("aria-checked", "true");
      await cards.nth(2).focus();
      await page.keyboard.press("Enter");
      await expect(cards.nth(2)).toHaveAttribute("aria-checked", "true");
      await cards.nth(0).click();
      await expect(cards.nth(0)).toHaveAttribute("aria-checked", "true");
      await expect(stage(page, 3).locator("[data-check]")).toHaveCount(1);
      await expect(cards.nth(0).locator("[data-check]")).toBeVisible();
      await expect(stage(page, 3).locator("[role=radio][tabindex='0']")).toHaveCount(1);
      await expect(stage(page, 3).locator("[data-submit-stage] button")).toBeEnabled();
    });

    await test.step("a typed stage refuses a non-number without using up the answer, and confirms what was typed", async () => {
      await openStage(page, 8);
      const s = stage(page, 8);
      await expect(s.locator("[data-submit-stage] button")).toBeDisabled();
      await s.locator("[data-answer]").fill("abc");
      await expect(s.locator("[data-submit-stage] button")).toBeDisabled();
      await expect(s).toContainText("Enter a number, for example 0.5.");
      await s.locator("[data-answer]").fill("1e3");
      await expect(s.locator("[data-submit-stage] button")).toBeDisabled();
      await s.locator("[data-answer]").fill("0,19");
      await expect(s.locator("[data-submit-stage] button")).toBeEnabled();
      await expect(s.locator("[data-answer]")).toHaveAttribute("maxlength", "40");
      await s.locator("[data-submit-stage] button").click();
      const dialog = page.getByRole("dialog");
      await expect(dialog).toContainText("Submit 0,19? One answer only.");
      await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
      await expect(dialog).toBeHidden();
      await expect(s.locator("[data-stage-saved]")).toHaveCount(0);
      await expect(s.locator("[data-answer]")).toHaveValue("0,19");
    });

    await test.step("the crossover builder: a refresh keeps the child, the keyboard places a bit, Esc cancels", async () => {
      await openStage(page, 6);
      await expect(stage(page, 6).locator("[data-figure]")).toHaveCount(0);
      await shotStage(page, 6, "sentinel-builder-empty-1440.png");
      for (const position of [1, 2, 3]) await tile(page, "A", position).dragTo(slot(page, position));
      expect(await child(page)).toBe("101???");
      await shotStage(page, 6, "sentinel-builder-half-1440.png");
      await page.reload();
      await openStage(page, 6);
      expect(await child(page), "a refresh keeps the bits being built").toBe("101???");
      // Keyboard only: pick a tile with Enter, Esc cancels, pick again with Space, drop on a slot with Enter.
      await tile(page, "B", 4).focus();
      await page.keyboard.press("Enter");
      await expect(tile(page, "B", 4)).toHaveAttribute("aria-pressed", "true");
      await page.keyboard.press("Escape");
      await expect(tile(page, "B", 4)).toHaveAttribute("aria-pressed", "false");
      await tile(page, "B", 4).focus();
      await page.keyboard.press("Space");
      await slot(page, 4).focus();
      await page.keyboard.press("Enter");
      expect(await child(page)).toBe("1010??");
      await expect(page.locator("[data-live]").first()).toContainText("child slot 4");
      await expect(slot(page, 4)).toHaveAttribute("aria-label", "Child slot 4: 0");
      await expect(tile(page, "B", 4)).toHaveAttribute("aria-label", "Parent B bit 4: 0");
      await expect(slot(page, 6)).toHaveAttribute("aria-label", "Child slot 6: empty");
      // Dropping on a filled slot replaces it; Mutate flips one slot at a time (moving the flip puts the first back).
      await tile(page, "A", 1).click();
      await slot(page, 4).click();
      expect(await child(page)).toBe("1011??");
      await tile(page, "B", 5).click();
      await slot(page, 5).click();
      await tile(page, "B", 6).click();
      await slot(page, 6).click();
      await stage(page, 6).locator("[data-mutate]").click();
      await slot(page, 5).click();
      expect(await child(page)).toBe("101101");
      await slot(page, 6).click();
      expect(await child(page), "flipping another slot moves the mutation: the first flips back").toBe("101110");
      await expect(stage(page, 6).locator("[data-star]")).toHaveCount(1);
      await expect(slot(page, 6).locator("[data-star]")).toBeVisible();
      await expect(slot(page, 6)).toHaveAttribute("aria-label", "Child slot 6: 0, flipped");
      await shotStage(page, 6, "sentinel-builder-mutation-1440.png");
      // Reset clears the child.
      await stage(page, 6).locator("[data-reset]").click();
      expect(await child(page)).toBe("??????");
      await expect(stage(page, 6).locator("[data-submit-stage] button")).toBeDisabled();
    });

    const expectResumed = async (what: string) => {
      await expect(card(page).locator("[data-sentinel-start]"), `${what}: no start screen again`).toHaveCount(0);
      await expect(card(page).locator('[data-stage-link="1"] [data-saved-mark]')).toContainText("Saved");
      await openStage(page, 1);
      await expect(stage(page, 1).locator("[data-stage-saved]")).toBeVisible();
      await expect(stage(page, 1).locator("svg")).toHaveCount(0);
      await openStage(page, 3);
      expect(await optionOrder(page, 3), `${what}: the cards keep this team's order`).toEqual(order3);
      await expect(stage(page, 3).locator("[data-stage-saved]")).toHaveCount(0);
      await openStage(page, 10);
      await expect(stage(page, 10).locator("[data-runs-left]")).toContainText("Tries left: 1 of 3");
      await expect(stage(page, 10).locator("[data-run]")).toHaveCount(2);
    };

    await test.step("a page refresh resumes with the saved stage, the runs and the budgets", async () => {
      await page.reload();
      await expectResumed("after a refresh");
    });

    await test.step("the last try is spent and nothing more can be asked", async () => {
      await openStage(page, 10);
      await ask(page, "Small", 3);
      await expect(stage(page, 10).locator("[data-runs-left]")).toContainText("Tries left: 0 of 3");
      await stage(page, 10).getByRole("group", { name: "Chunk size" }).getByRole("radio", { name: "Large" }).check();
      await stage(page, 10).getByRole("group", { name: /^Top-k/ }).getByRole("radio", { name: "Top-1" }).check();
      await expect(stage(page, 10).locator("[data-ask]")).toBeDisabled();
      await page.reload();
      await openStage(page, 10);
      await expect(stage(page, 10).locator("[data-runs-left]")).toContainText("Tries left: 0 of 3");
    });

    await test.step("a paused round freezes the input", async () => {
      await adminApi.send("POST", "/event/admin/round/timer/pause");
      await expect(card(page).locator("ui-status-chip", { hasText: "Paused" })).toBeVisible();
      await openStage(page, 9);
      await expect(stage(page, 9).locator("[data-answer]")).toBeDisabled();
      await expect(stage(page, 9).locator("[data-submit-stage] button")).toBeDisabled();
      await expect(stage(page, 9)).toContainText("Input is frozen right now.");
      await openStage(page, 3);
      await expect(stage(page, 3).locator("[role=radio]").first()).toHaveAttribute("aria-disabled", "true");
      await adminApi.send("POST", "/event/admin/round/timer/resume");
      await expect(stage(page, 3).locator("[role=radio]").first()).toHaveAttribute("aria-disabled", "false");
      await openStage(page, 9);
      await expect(stage(page, 9).locator("[data-answer]")).toBeEnabled();
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
        expect(await optionOrder(again.page, 3), "the same team sees the same order in a new browser").toEqual(order3);
        await openStage(again.page, 10);
        await expect(stage(again.page, 10).locator("[data-runs-left]")).toContainText("Tries left: 0 of 3");
        // One answer only: asked first, then locked.
        await lockChoice(again.page);
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

test("sentinel: the crossover builder works with taps on a phone and submits the child", async ({ browser }) => {
  test.setTimeout(240000);
  const team = await openRolePage(browser, "team", NARROW);
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
    await openStage(page, 6);

    await test.step("the empty builder fits 390 px and shows nothing about how to solve it", async () => {
      await expectNoOverflow(page, "builder at 390");
      await expectContained(page, 6, "390");
      const third = (await tile(page, "A", 3).boundingBox())!;
      expect(third.width, "tiles are big enough to tap").toBeGreaterThanOrEqual(34);
      await expectNeutral(page);
      await expect(stage(page, 6).locator("[data-status]")).toHaveText("Fill the child, then apply the mutation.");
      await shotStage(page, 6, "sentinel-builder-empty-390.png");
    });

    await test.step("tap-to-place fills the child; the mutation flips position 5", async () => {
      for (const [row, position] of [["A", 1], ["A", 2], ["A", 3]] as const) {
        await tile(page, row, position).click();
        await slot(page, position).click();
      }
      expect(await child(page)).toBe("101???");
      await shotStage(page, 6, "sentinel-builder-half-390.png");
      for (const [row, position] of [["B", 4], ["B", 5], ["B", 6]] as const) {
        await tile(page, row, position).click();
        await slot(page, position).click();
      }
      expect(await child(page)).toBe("101011");
      await stage(page, 6).locator("[data-mutate]").click();
      await slot(page, 5).click();
      expect(await child(page)).toBe("101001");
      await shotStage(page, 6, "sentinel-builder-mutation-390.png");
      await stage(page, 6).locator("[data-mutate]").click();
      await expectNoOverflow(page, "built child at 390");
    });

    await test.step("Submit asks 'Submit <bits>? One answer only.', sends the bits once and shows the child as Saved", async () => {
      const sent = page.waitForRequest((request) => request.method() === "POST" && request.url().includes("/stages/6/submit"));
      await stage(page, 6).locator("[data-submit-stage] button").click();
      const dialog = page.getByRole("dialog");
      await expect(dialog).toContainText("Submit 101001? One answer only.");
      await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
      await expect(dialog).toBeHidden();
      await expect(stage(page, 6).locator("[data-stage-saved]")).toHaveCount(0);
      await stage(page, 6).locator("[data-submit-stage] button").click();
      await page.getByRole("dialog").getByRole("button", { name: /^(Confirm|Submit)/ }).click();
      expect((await sent).postDataJSON()).toEqual({ answer: "101001" });
      await expect(stage(page, 6).locator("[data-stage-saved]")).toContainText("Saved");
      await expect(stage(page, 6).locator("[data-submitted-child]")).toContainText("101001");
      await expect(stage(page, 6).locator("[data-crossover]")).toHaveCount(0);
      await shotStage(page, 6, "sentinel-builder-submitted-390.png");
      // It stays locked after a refresh, and the child is still shown.
      await page.reload();
      await openStage(page, 6);
      await expect(stage(page, 6).locator("[data-stage-saved]")).toBeVisible();
      await expect(stage(page, 6).locator("[data-submitted-child]")).toContainText("101001");
      await page.setViewportSize(WIDE);
      await openStage(page, 6);
      await shotStage(page, 6, "sentinel-builder-submitted-1440.png");
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
