import { test, expect, Page } from "@playwright/test";
import zlib from "node:zlib";
import {
  EventAdminShortcuts,
  EventApiSession,
  RehearsalAccounts,
  readRehearsalAccounts,
  resetRehearsal,
  setPracticeKeys,
} from "./event-api-helper";
import { openRolePage, signIn } from "./event-ui-helper";
import { BZ2_SAMPLE_TEXT, buildServerFixture } from "./linux-room-fixture";
import { ask, expectFindChallenge, screen, terminal } from "./linux-terminal-helper";

let accounts: RehearsalAccounts;
const WIDE = { width: 1440, height: 900 };
const BOOT = { timeout: 180000 };
const BANNER = "Noor's server is mounted.";
/** Team 09 is in group C: the Linux room in round 1. */
const TEAM = 8;

test.beforeAll(() => {
  accounts = readRehearsalAccounts();
});

test.beforeEach(() => resetRehearsal());

async function bootSeconds(page: Page): Promise<number> {
  const shown = (await terminal(page).locator(".linux-terminal__status").textContent()) ?? "";
  return Number(/Ready in ([\d.]+) s/.exec(shown)?.[1]);
}

/** Restart terminal and wait until the new VM is ready (the old one is still "ready" for a moment). */
async function restart(page: Page) {
  await terminal(page).getByRole("button", { name: "Restart terminal", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Confirm", exact: true }).click();
  await expect(terminal(page)).not.toHaveAttribute("data-state", "ready");
  await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
  await expect(terminal(page)).toContainText(BANNER);
}

async function startTerminal(page: Page) {
  await page.getByRole("button", { name: "Start terminal", exact: true }).click();
}

test("terminal tools: file, base64, bzip2, zip and GNU find in a 2000-file package, exact dates and owners, no manifest, the terminal clock", async ({
  browser,
}) => {
  test.setTimeout(900000);
  const fixture = buildServerFixture();
  expect(fixture.fileCount).toBe(2000);
  const team = await openRolePage(browser, "team", WIDE);
  const adminApi = await EventApiSession.signIn(accounts.adminEmail, accounts.adminPassword);
  const control = new EventAdminShortcuts(adminApi);
  const managerApi = await EventApiSession.signIn(accounts.managers.LINUX, accounts.seedPassword);
  try {
    await setPracticeKeys(accounts, ["LINUX"]);
    await control.setRoomActivity("AI", null);
    await control.setRoomActivity("LINUX", "LINUX_TERMINAL");
    const room = await control.roomId("LINUX");
    await managerApi.upload(`/event/rooms/${room}/terminal-bundle`, "server.zip", fixture.zip);
    await control.setRound(1);
    await signIn(team.page, accounts.members[TEAM], accounts.seedPassword);
    await control.startTimer();
    await control.openRoom("LINUX");
    const page = team.page;

    await test.step("the terminal starts with the package and the tools, and says nothing about the setup", async () => {
      await startTerminal(page);
      await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
      await expect(terminal(page)).toContainText(BANNER);
      expect(await screen(page)).not.toMatch(/gzip|tar -xf|__DEVFEST|devfest-tools|devfest-bundle/);
      test.info().annotations.push({ type: "boot (2000 files + tools)", description: `${await bootSeconds(page)} s` });
      const memory = await ask(page, "echo mem=$(free -m | grep Mem | tr -s ' ')", "mem");
      test.info().annotations.push({ type: "free -m (2000 files + tools)", description: memory });
      expect(await ask(page, "echo mnt=$(ls /mnt | wc -l)", "mnt")).toBe("0");
      expect(await ask(page, "echo fpath=$(command -v find)", "fpath")).toBe("/usr/local/bin/find");
      expect(await ask(page, "echo gnu=$(find --version | head -n 1 | cut -c1-20)", "gnu")).toBe("find (GNU findutils)");
      expect(await ask(page, "echo busy=$(busybox | head -n 1 | cut -c1-14)", "busy")).toBe("BusyBox v1.28.");
    });

    await test.step("file identifies text, gzip, tar.gz, zip, bzip2 and binary files", async () => {
      for (const [index, kind] of fixture.types.entries()) {
        const answer = await ask(page, `echo ft${index}=$(file -b ${kind.path} | cut -d, -f1)`, `ft${index}`);
        expect(answer.startsWith(kind.starts), `${kind.path}: ${answer}`).toBe(true);
      }
      expect(await ask(page, "echo fm=$(file -b --mime-type /home/server/types/sample.zip)", "fm")).toBe("application/zip");
    });

    await test.step("base64 encodes, decodes and round-trips a binary file", async () => {
      expect(await ask(page, "echo e1=$(printf 'abc' | base64)", "e1")).toBe("YWJj");
      expect(await ask(page, "echo e2=$(printf 'DevFest 123' | base64 | base64 -d)", "e2")).toBe("DevFest 123");
      const blob = "/home/server/types/blob.bin";
      expect(
        await ask(page, `echo e3=$([ "$(base64 ${blob} | base64 -d | cksum)" = "$(cksum < ${blob})" ] && echo same || echo different)`, "e3"),
      ).toBe("same");
    });

    await test.step("bzip2, bunzip2 and bzcat round-trip text and binary data", async () => {
      expect(await ask(page, "echo z1=$(printf 'hello bzip2' | bzip2 -c | bzcat)", "z1")).toBe("hello bzip2");
      expect(await ask(page, "echo z2=$(printf 'round trip' | bzip2 -c | bunzip2 -c)", "z2")).toBe("round trip");
      expect(await ask(page, "echo z3=$(bzcat /home/server/types/sample.bz2 | head -n 1)", "z3")).toBe(BZ2_SAMPLE_TEXT.split("\n")[0]);
      expect(await ask(page, "echo z4=$(bzcat /home/server/types/sample.bz2 | wc -c)", "z4")).toBe(String(BZ2_SAMPLE_TEXT.length));
      const blob = "/home/server/types/blob.bin";
      expect(
        await ask(
          page,
          `cp ${blob} /tmp/b.bin && bzip2 -k /tmp/b.bin && echo z5=$([ "$(bunzip2 -c /tmp/b.bin.bz2 | cksum)" = "$(cksum < ${blob})" ] && echo same || echo different)`,
          "z5",
        ),
      ).toBe("same");
      expect((await ask(page, "echo z6=$(file -b /tmp/b.bin.bz2 | cut -d, -f1)", "z6")).startsWith("bzip2 compressed data")).toBe(true);
    });

    await test.step("zip then unzip round-trips, and file recognizes the new archive", async () => {
      expect(
        await ask(page, "cd /tmp && printf 'zip me' > zz.txt && zip -q zz.zip zz.txt && echo zp=$(unzip -p zz.zip zz.txt); cd ~", "zp"),
      ).toBe("zip me");
      expect((await ask(page, "echo zf=$(file -b /tmp/zz.zip | cut -d, -f1)", "zf")).startsWith("Zip archive data")).toBe(true);
      expect(await ask(page, "echo zl=$(unzip -l /tmp/zz.zip | grep -c zz.txt)", "zl")).toBe("1");
    });

    await test.step("find supports -printf, -newermt, -mtime, -user, -size and -name", async () => {
      expect(await ask(page, "echo fp=$(find /home/server/uploads -name 'handover-0427.bak' -printf '%s:%u:%m')", "fp")).toBe("1337:tech9:644");
      expect(await ask(page, "echo nm=$(find /home/server/dated -type f -newermt '2026-03-04 00:00:00' | wc -l)", "nm")).toBe("3");
      expect(await ask(page, "echo mt=$(find /home/server/dated -type f -mtime +100 | wc -l)", "mt")).toBe("4");
      expect(await ask(page, "echo us=$(find /home/server/types -user guard2 -type f | wc -l)", "us")).toBe("3");
      expect(await ask(page, "echo sz=$(find /home/server/types -size 4093c -type f | wc -l)", "sz")).toBe("1");
      expect(await ask(page, "echo nmm=$(find /home/server/types -name 'sample.*' | wc -l)", "nmm")).toBe("5");
      expect(await ask(page, "echo cnt=$(find /home/server -type f | wc -l)", "cnt")).toBe(String(fixture.fileCount));
    });

    await test.step("each parameter alone returns several files; all four together return exactly the target", async () => {
      await expectFindChallenge(page, fixture, fixture.roots);
    });

    await test.step("the manifest exists nowhere in the guest", async () => {
      expect(await ask(page, "echo mf=$(find / -xdev -name 'devfest-manifest*' 2>/dev/null | wc -l)", "mf")).toBe("0");
      // The emulated guest reads the 2000 package files in a while.
      // (The shell's own history file holds the typed command, so it is left out.)
      expect(await ask(page, "echo mg=$(printf 'files:'; find /home /root /etc /tmp -type f ! -name .ash_history -exec grep -l 'path,owner,group,mode' {} + 2>/dev/null | head -n 3 | tr '\n' ' ')", "mg", 120000)).toBe("files:");
    });

    await test.step("owners, groups, modes, sizes and dates match the manifest exactly", async () => {
      for (const [index, sample] of fixture.samples.entries()) {
        const expected = `${sample.user}:${sample.group}:${sample.mode}:${sample.size}:${sample.mtime}`;
        const found = await ask(page, `echo sm${index}=$(find ${sample.path} -printf '%u:%g:%m:%s:%T@' | cut -d. -f1)`, `sm${index}`);
        expect(found, sample.path).toBe(expected);
      }
      const listing = await ask(page, "echo ls1=$(ls -l --full-time /home/server/types/blob.bin | tr -s ' ' | cut -d' ' -f6,7)", "ls1");
      expect(listing).toBe("2026-01-02 03:04:05");
      // guard2 is the first owner by name (uid 2000) and has its own group (gid 2000).
      expect(await ask(page, "echo ls2=$(ls -ln --full-time /home/server/types/blob.bin | tr -s ' ' | cut -d' ' -f1,3,4)", "ls2")).toBe("-rw------- 2000 2000");
    });

    await test.step("folders keep their dates although their files were written after them", async () => {
      for (const [index, folder] of fixture.datedDirectories.entries()) {
        expect(await ask(page, `echo dd${index}=$(find ${folder.path} -maxdepth 0 -printf '%T@' | cut -d. -f1)`, `dd${index}`)).toBe(String(folder.mtime));
      }
      expect(await ask(page, "echo ld=$(ls -ld --full-time /home/server/archive | tr -s ' ' | cut -d' ' -f6,7)", "ld")).toBe("2024-02-29 13:45:10");
      expect(await ask(page, "echo lf=$(ls -l --full-time /home/server/archive/2024-a.txt | tr -s ' ' | cut -d' ' -f6,7)", "lf")).toBe("2024-02-29 13:45:10");
      expect(
        await ask(page, "echo win=$(find /home/server/archive -newermt '2024-02-29 13:45:09' ! -newermt '2024-02-29 13:45:11' | wc -l)", "win"),
      ).toBe("3");
    });

    await test.step("with the terminal clock set, date and find -mtime give the same result on any day, also after a restart", async () => {
      await managerApi.send("PUT", `/event/rooms/${room}/terminal-bundle/clock`, { clock: fixture.clock.value });
      const check = async (label: string) => {
        expect(await ask(page, `echo ${label}d=$(date -u '+%Y-%m-%d')`, `${label}d`), "the date is the clock's").toBe("2026-03-14");
        expect(await ask(page, `echo ${label}h=$(date -u +%H)`, `${label}h`)).toBe("09");
        for (const age of fixture.clock.ages) {
          expect(await ask(page, `echo ${label}a${age.days}=$(find /home/server/dated -type f -mtime ${age.days})`, `${label}a${age.days}`), `-mtime ${age.days}`).toBe(age.path);
        }
        expect(await ask(page, `echo ${label}p=$(find /home/server/dated -type f -mtime +9 | wc -l)`, `${label}p`)).toBe("2");
        expect(await ask(page, `echo ${label}n=$(find /home/server/dated -type f -newermt '2026-03-14 00:00:00' | wc -l)`, `${label}n`)).toBe("1");
      };
      await restart(page);
      await check("c1");
      await restart(page);
      await check("c2");
    });

    await test.step("the clock is cleared and the terminal uses real time again", async () => {
      await managerApi.send("PUT", `/event/rooms/${room}/terminal-bundle/clock`, { clock: null });
      await restart(page);
      expect(Number(await ask(page, "echo ry=$(date -u +%s)", "ry"))).toBeGreaterThan(fixture.clock.epoch + 30 * 24 * 3600);
    });

    await test.step("a tools download that fails shows a clear error, not a half-ready shell, and a retry works", async () => {
      await page.reload();
      await page.route("**/devfest-tools.tgz*", (route) => route.abort());
      await startTerminal(page);
      await expect(terminal(page)).toHaveAttribute("data-state", "failed", BOOT);
      await expect(terminal(page).getByRole("alert")).toContainText("extra tools could not be downloaded");
      await page.unroute("**/devfest-tools.tgz*");
      await terminal(page).getByRole("button", { name: "Try again", exact: true }).click();
      await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
      expect(await ask(page, "echo again=$(command -v bzip2)", "again")).toBe("/usr/local/bin/bzip2");
    });

    await test.step("tools the guest cannot unpack end in a clear error, and a retry downloads them again", async () => {
      await page.reload();
      await page.route("**/devfest-tools.tgz*", (route) =>
        route.fulfill({ status: 200, contentType: "application/gzip", body: zlib.gzipSync(Buffer.from("this is not a tar archive")) }),
      );
      await startTerminal(page);
      await expect(terminal(page)).toHaveAttribute("data-state", "failed", BOOT);
      await expect(terminal(page).getByRole("alert")).toContainText("extra tools could not be installed");
      await page.unroute("**/devfest-tools.tgz*");
      await terminal(page).getByRole("button", { name: "Try again", exact: true }).click();
      await expect(terminal(page)).toHaveAttribute("data-state", "ready", BOOT);
      expect(await ask(page, "echo fine=$(command -v zip)", "fine")).toBe("/usr/local/bin/zip");
    });
  } finally {
    await adminApi.dispose();
    await managerApi.dispose();
    await team.page.context().close().catch(() => {});
  }
  expect(team.problems.filter((problem) => !problem.includes("devfest-tools") && !problem.includes("ERR_FAILED"))).toEqual([]);
});
