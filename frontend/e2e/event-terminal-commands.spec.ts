import { test, expect, Page } from "@playwright/test";
import {
  EventAdminShortcuts,
  EventApiSession,
  RehearsalAccounts,
  readRehearsalAccounts,
  resetRehearsal,
} from "./event-api-helper";
import { openRolePage, signIn } from "./event-ui-helper";
import { buildLinuxRoomFixture } from "./linux-room-fixture";
import { ask, screen, terminal } from "./linux-terminal-helper";

let accounts: RehearsalAccounts;
const WIDE = { width: 1440, height: 900 };
const BOOT = { timeout: 120000 };

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

async function type(page: Page, text: string) {
  await terminal(page).locator(".xterm").click();
  await page.keyboard.type(text);
}

test("terminal commands: nano edits and saves a file, and tree, less, strings, diff and which run", async ({ browser }) => {
  test.setTimeout(420000);
  const manager = await openRolePage(browser, "manager", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  const managerApi = await EventApiSession.signIn(accounts.managers.LINUX, accounts.seedPassword);
  try {
    const room = await control.roomId("LINUX");
    await managerApi.upload(`/event/rooms/${room}/terminal-bundle`, "rami-server.zip", buildLinuxRoomFixture().zip);
    const page = manager.page;
    await signIn(page, accounts.managers.LINUX, accounts.seedPassword);
    await page.goto(`/event/room/${room}`);
    await page.locator("event-terminal-files-card").getByRole("button", { name: "Test terminal", exact: true }).click();
    await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
    await terminal(page).scrollIntoViewIfNeeded();

    await test.step("every added command resolves inside /usr/local, and the guest's TERM is the one the archive carries", async () => {
      expect(await ask(page, "echo wn=$(command -v nano)", "wn")).toBe("/usr/local/bin/nano");
      expect(await ask(page, "echo wt=$(command -v tree)", "wt")).toBe("/usr/local/bin/tree");
      expect(await ask(page, "echo term=$TERM", "term")).toBe("linux");
      // The commands that were already in the image are still there.
      for (const command of ["less", "strings", "diff", "which"]) {
        expect(await ask(page, `echo has_${command}=$(command -v ${command} | wc -l)`, `has_${command}`)).toBe("1");
      }
    });

    await test.step("nano opens a file, arrow keys move, typing works, Ctrl+O saves and Ctrl+X exits", async () => {
      expect(await ask(page, "echo seeded=$(printf 'one\\ntwo\\n' > ~/notes.txt; wc -l < ~/notes.txt)", "seeded")).toBe("2");
      await type(page, "nano ~/notes.txt");
      await page.keyboard.press("Enter");
      await expect(terminal(page)).toContainText("GNU nano", { timeout: 20000 });
      await expect(terminal(page)).toContainText("notes.txt");
      await expect(terminal(page)).toContainText("^X Exit");
      // The file is shown and the cursor starts at its top-left.
      await expect(terminal(page)).toContainText("two");
      await page.keyboard.type("A"); // Aone
      await page.keyboard.press("ArrowDown"); // line 2, after the first character
      await page.keyboard.type("B"); // tBwo
      await page.keyboard.press("ArrowLeft");
      await page.keyboard.press("ArrowLeft"); // start of line 2
      await page.keyboard.type("C"); // CtBwo
      await expect(terminal(page)).toContainText("Aone");
      await expect(terminal(page)).toContainText("CtBwo");
      await page.keyboard.press("Control+O");
      await expect(terminal(page)).toContainText("Write to File:");
      await page.keyboard.press("Enter");
      await expect(terminal(page)).toContainText("Wrote 2 lines");
      await page.keyboard.press("Control+X");
    });

    await test.step("after nano exits the screen is clean and the file holds the edits", async () => {
      expect(await ask(page, "echo l1=$(head -n 1 ~/notes.txt) l2=$(tail -n 1 ~/notes.txt)", "l1")).toBe("Aone l2=CtBwo");
      const shown = await screen(page);
      for (const leftover of ["GNU nano", "^X Exit", "Write Out", "Write to File:", "Wrote 2 lines"]) {
        expect(shown, `nano's "${leftover}" is still on the screen`).not.toContain(leftover);
      }
      // What the team had on the screen before nano is back.
      expect(shown).toContain("Rami's server is mounted.");
    });

    await test.step("nano leaves a file alone when the team quits without changes", async () => {
      await type(page, "nano ~/notes.txt");
      await page.keyboard.press("Enter");
      await expect(terminal(page)).toContainText("GNU nano", { timeout: 20000 });
      await page.keyboard.press("Control+X");
      expect(await ask(page, "echo stillok=$(wc -l < ~/notes.txt)", "stillok")).toBe("2");
    });

    await test.step("tree draws a folder", async () => {
      expect(await ask(page, "echo tr=$(tree -L 1 /srv/rami | tail -n 1 | tr ' ' _)", "tr")).toMatch(/_directories?,_\d+_files?$/);
    });

    await test.step("less, strings, diff and which run", async () => {
      expect(await ask(page, "echo lessout=$(printf 'x\\ny\\n' | less | wc -l)", "lessout")).toBe("2");
      expect(await ask(page, "echo strs=$(printf 'abcdefg\\000hij\\000klmnop' | strings | wc -l)", "strs")).toBe("2");
      expect(
        await ask(page, "echo dif=$(printf 'a\\n' > /tmp/a.txt; printf 'b\\n' > /tmp/b.txt; diff /tmp/a.txt /tmp/b.txt | head -n 1)", "dif"),
      ).toBe("--- /tmp/a.txt");
      expect(await ask(page, "echo wh=$(which nano)", "wh")).toBe("/usr/local/bin/nano");
    });
  } finally {
    await adminApi.dispose();
    await managerApi.dispose();
    await manager.page.context().close().catch(() => {});
  }
  expect(manager.problems).toEqual([]);
});
