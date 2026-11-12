import { test, expect, Page } from "@playwright/test";
import {
  EventAdminShortcuts,
  EventApiSession,
  RehearsalAccounts,
  readRehearsalAccounts,
  resetRehearsal,
  setPracticeKeys,
} from "./event-api-helper";
import { openRolePage, signIn } from "./event-ui-helper";
import { buildLinuxRoomFixture } from "./linux-room-fixture";
import { ask, terminal } from "./linux-terminal-helper";

let accounts: RehearsalAccounts;
const WIDE = { width: 1440, height: 900 };
const BOOT = { timeout: 120000 };
/** Team 09–12 are group C: the Linux room in round 1. */
const LINUX_TEAM = 8;
const NOT_SAVABLE = "Your terminal cannot be saved in this browser. A page refresh will restart it.";

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

/** Whether the browser holds the team's saved terminal (the one record in IndexedDB). */
async function savedTerminalExists(page: Page): Promise<boolean> {
  return page.evaluate(
    () =>
      new Promise<boolean>((resolve) => {
        const open = indexedDB.open("devfest-terminal", 1);
        open.onupgradeneeded = () => open.result.createObjectStore("snapshot");
        open.onerror = () => resolve(false);
        open.onsuccess = () => {
          const database = open.result;
          const get = database.transaction("snapshot", "readonly").objectStore("snapshot").get("current");
          get.onsuccess = () => {
            database.close();
            resolve(get.result !== undefined);
          };
          get.onerror = () => {
            database.close();
            resolve(false);
          };
        };
      }),
  );
}

/** Waits until the terminal finished a save in this page (its data-saves counter), so a reload cannot beat the save. */
async function waitForSave(page: Page): Promise<void> {
  await expect(terminal(page)).not.toHaveAttribute("data-saves", "0", { timeout: 40000 });
  await expect.poll(() => savedTerminalExists(page)).toBe(true);
}

/** Starts the terminal, which boots Linux with the room's package, and waits for the prompt. */
async function startTerminal(page: Page): Promise<void> {
  await page.getByRole("button", { name: "Start terminal", exact: true }).click();
  await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
}

async function setUp(browser: import("@playwright/test").Browser) {
  const fixture = buildLinuxRoomFixture();
  const team = await openRolePage(browser, "linux team", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  const managerApi = await EventApiSession.signIn(accounts.managers.LINUX, accounts.seedPassword);
  await setPracticeKeys(accounts, ["LINUX"]);
  await control.setRoomActivity("AI", null);
  const room = await control.roomId("LINUX");
  await managerApi.upload(`/event/rooms/${room}/terminal-bundle`, "rami-server.zip", fixture.zip);
  await control.setRound(1);
  await signIn(team.page, accounts.members[LINUX_TEAM], accounts.seedPassword);
  await control.startTimer();
  await control.openRoom("LINUX");
  // The terminal asks before a reload while it runs; the test accepts, like a person pressing Reload.
  team.page.on("dialog", (dialog) => void dialog.accept());
  return { team, control, adminApi, managerApi };
}

test("terminal resume: a file made in the terminal is still there after a page refresh, with no click", async ({ browser }) => {
  test.setTimeout(600000);
  const { team, control, adminApi, managerApi } = await setUp(browser);
  const page = team.page;
  try {
    let bootSeconds = 0;
    await test.step("the team starts the terminal and creates a file", async () => {
      const started = Date.now();
      await startTerminal(page);
      bootSeconds = (Date.now() - started) / 1000;
      expect(await ask(page, "echo made=$(echo kept > ~/keep.txt; cat ~/keep.txt)", "made")).toBe("kept");
      await waitForSave(page);
    });

    await test.step("after a reload the terminal comes back by itself with the file, the history and the screen", async () => {
      const reloaded = Date.now();
      await page.reload();
      // No "Start terminal" click: a saved terminal of a running round starts on its own.
      await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
      const resumeSeconds = (Date.now() - reloaded) / 1000;
      test.info().annotations.push({
        type: "reload to prompt",
        description: `${resumeSeconds.toFixed(1)} s with a snapshot; first boot (click to prompt) ${bootSeconds.toFixed(1)} s`,
      });
      await expect(page.getByRole("button", { name: "Start terminal", exact: true })).toHaveCount(0);
      // The old screen is drawn again, and the file is in the restored Linux.
      await expect(terminal(page)).toContainText("made=kept");
      expect(await ask(page, "echo again=$(cat ~/keep.txt)", "again")).toBe("kept");
      // The shell's own history survived with the VM.
      expect(await ask(page, "echo lines=$(ls ~/keep.txt | wc -l)", "lines")).toBe("1");
    });

    await test.step("signing out deletes the saved terminal", async () => {
      await waitForSave(page);
      await page.getByRole("button", { name: "Sign out", exact: true }).click();
      await expect(page.getByRole("button", { name: "Sign in", exact: true })).toBeVisible();
      await expect.poll(() => savedTerminalExists(page)).toBe(false);
    });

    await test.step("after signing in again there is nothing to resume: the team starts a new terminal", async () => {
      await signIn(page, accounts.members[LINUX_TEAM], accounts.seedPassword);
      await expect(page.getByRole("button", { name: "Start terminal", exact: true })).toBeVisible();
      await startTerminal(page);
      expect(await ask(page, "echo second=$(echo two > ~/second.txt; cat ~/second.txt)", "second")).toBe("two");
      await waitForSave(page);
    });

    await test.step("Restart terminal deletes the saved terminal and starts clean", async () => {
      await page.getByRole("button", { name: "Restart terminal", exact: true }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Confirm", exact: true }).click();
      // The old terminal is still "ready" until the restart begins; wait for the new boot.
      await expect(terminal(page)).not.toHaveAttribute("data-state", "ready");
      await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
      expect(await ask(page, "echo gone=$(ls ~/second.txt 2>/dev/null | wc -l)", "gone")).toBe("0");
      // Typing after a restart is saved again, from the clean terminal.
      await waitForSave(page);
    });

    await test.step("the end of the round removes the terminal and the saved terminal without any click", async () => {
      await control.endRound();
      await expect(terminal(page)).toHaveCount(0, { timeout: 8000 });
      await expect.poll(() => savedTerminalExists(page)).toBe(false);
      await page.reload();
      await expect(page.getByRole("button", { name: "Start terminal", exact: true })).toHaveCount(0);
      await expect(terminal(page)).toHaveCount(0);
    });
  } finally {
    await adminApi.dispose();
    await managerApi.dispose();
    await page.context().close().catch(() => {});
  }
  // Signing out leaves a few in-flight board requests that answer 401; that is the sign-out itself.
  expect(team.problems.filter((problem) => !problem.includes("401 (Unauthorized)"))).toEqual([]);
});

test("terminal resume: when the browser cannot store the terminal the team keeps working and sees one small notice", async ({ browser }) => {
  test.setTimeout(480000);
  const { team, adminApi, managerApi } = await setUp(browser);
  const page = team.page;
  try {
    // IndexedDB refuses every write, as in some private windows.
    await page.addInitScript(() => {
      const original = IDBObjectStore.prototype.put;
      IDBObjectStore.prototype.put = function (this: IDBObjectStore, ...args: Parameters<IDBObjectStore["put"]>) {
        if (this.name === "snapshot") throw new DOMException("blocked", "QuotaExceededError");
        return original.apply(this, args);
      };
    });
    await page.reload();
    await startTerminal(page);
    expect(await ask(page, "echo works=$((6*7))", "works")).toBe("42");
    await expect(page.locator("[data-save-notice]")).toHaveText(NOT_SAVABLE, { timeout: 40000 });
    await expect(page.locator("[data-save-notice]")).toHaveCount(1);
    // The terminal keeps running, and no dialog or error alert appeared.
    expect(await ask(page, "echo still=$((7*7))", "still")).toBe("49");
    await expect(terminal(page).locator("ui-alert")).toHaveCount(0);
    expect(await savedTerminalExists(page)).toBe(false);
  } finally {
    await adminApi.dispose();
    await managerApi.dispose();
    await page.context().close().catch(() => {});
  }
});
