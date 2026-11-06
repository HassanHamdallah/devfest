# Linux room: guide for the challenge author

The Linux room gives each team a Linux shell that runs **inside its own browser tab** (no server work, no internet).
This guide is for the room's manager (or the admin) who prepares what is inside it. Rules for everyone:
[EVENT-RULES.md](EVENT-RULES.md) §18. This page covers the **server package**, the **team form** and the **organizer
dashboard**.

## 1. What a server package is

One ZIP file, uploaded on your room board under **Terminal files → Server package**. When a team's terminal starts,
the ZIP's files appear in the team's Linux **at the paths written in the ZIP, counted from `/`**, with the owners and
modes you choose, before the shell is handed over. The team then explores a believable server instead of a few text
files: `srv/rami/logs/a.log` becomes `/srv/rami/logs/a.log`.

While a package exists, teams get **only the package**; the room's text files (the older mechanism) are not used.
Remove the package to go back to them. Rooms without a package behave exactly as before.

- The shell starts in the home directory `~` (`/root`). The team is `root` in its own private VM, so file modes do not
  stop them from reading anything; owners and groups matter for `ls -l`, `find -user` and `find -group` challenges.
- The banner says: *Rami's server is mounted. There is no internet in this terminal.*
- Nothing is saved. A restart (and a page refresh) starts again from your package.

## 2. The ZIP

| Rule | Limit |
|---|---|
| ZIP upload | up to **25 MB** |
| Entries (files and folders) | up to **5000** |
| Unpacked size | up to **20 MB**, counted from the bytes actually inflated (a "zip bomb" is stopped at the limit, whatever its header claims) |
| Paths | relative, written **without** a leading `/`; no `..`, no `.` or empty parts (`a//b`), no backslashes, no NUL or other control characters; at most 255 bytes in total and 100 bytes for the last folder or file name |
| Protected places | nothing under `bin`, `sbin`, `lib`, `usr/bin`, `usr/sbin`, `dev`, `proc`, `sys`, `mnt`, and not `etc/passwd` or `etc/group` (they belong to the Linux image; the terminal itself adds owners to those two files) |
| Names | a path may appear only once; a file cannot also be a folder |

Everything else under `/` is fine: `srv`, `home`, `var`, `opt`, `tmp`, `root`, other files under `etc`, new top-level
folders. Binary files are copied byte for byte.

Defaults when no manifest rule applies: owner `root`, group `root`, files `0644`, folders `0755`. File times come from
the ZIP entries when the manifest gives none.

**ZIP dates can shift with time zones.** A classic ZIP entry stores a wall-clock time without a zone, and the tool that
zipped the files (or the one that unzipped them before you re-zipped) may have converted it. The terminal reads such a
time as UTC, so a file saved at 09:26 in a UTC+3 office can show 09:26 or 06:26 depending on how it was zipped. A ZIP
that carries extended timestamps (for example one made by Info-ZIP's `zip`) is exact. **When a date matters, set it in
the manifest** (next section): that is the only way to get an exact date, whatever machine made the ZIP.

**Folders.** A folder is written into the archive only when a manifest rule covers it **or** it is empty (an empty
folder entry in the ZIP is kept). Other parent folders are simply created as needed, so a package never changes the
owner, mode or date of a folder the image already has (for example `/tmp`). A folder that is written goes **after
everything below it** in the archive, because extracting a file changes its folder's modification time: written last,
the folder keeps exactly the date you set (checked in the real terminal with `ls -l --full-time` and `find -mtime`).

A symbolic link inside a ZIP is installed as a plain file containing the target path; the archive format used here
carries no links.

If anything is wrong, **every problem is listed at once** and nothing is stored. Replacing a package is audited (SHA-256
and counts only, never file content), and like all content the package is read-only once the final opens.

## 3. The optional manifest: `devfest-manifest.csv`

A file with exactly this name in the **root** of the ZIP sets owners, groups, modes and, optionally, modification
dates. It is **not installed**: it exists nowhere in the team's Linux (nor on the server after the upload).

```
path,owner,group,mode,mtime
/srv/rami/**,rami,ops,0640,2026-03-14T09:26:00Z
/srv/rami/uploads/handover-0427.bak,tech9,tech9,0644,2025-12-24T18:00:00Z
/home/guard2/**,guard2,guard2,0600
/home/server/archive/**,rami,rami,0644,2024-02-29T13:45:10Z
```

- One rule per line: `path,owner,group,mode[,mtime]`. The header line, blank lines and lines starting with `#` are
  optional / ignored. A four-column manifest (no `mtime`) is still valid.
- **mtime** (optional): a UTC time as `2026-03-14T09:26:00Z` (exactly this form, with seconds and the `Z`; years 1970 to
  2099; a date that does not exist, such as `2026-02-30`, is refused). Blank keeps the date of the ZIP entry. It applies
  to files **and folders**: a `/**` rule dates the folder itself and everything below it. Like every field, the most
  specific rule decides, and it decides **all** of them: an exact rule with a blank `mtime` does not inherit the date of
  a `/**` rule above it. Invalid dates are listed with all the other problems. Wrap a field in double quotes if it contains a comma. The leading `/` of a path is optional.
- A path ending in `/**` covers that folder **and everything below it**; a plain path covers exactly that file or
  folder. **The most specific rule wins**: an exact path beats any `/**` rule, a deeper folder beats a shallower one.
- **owner** and **group**: lower-case letters, digits, `_` and `-` (up to 32, starting with a letter or `_`), or
  `root`. **mode**: 3 or 4 octal digits, e.g. `0644`, `600`, `0750`.
- A folder covered by a `/**` rule gets the rule's mode **plus execute wherever it has read** (`0640` gives `0750`), so
  the files below it stay reachable. A folder named exactly by a rule gets exactly that mode.
- A rule that matches nothing in the package is an error (it is usually a typo), and so is a repeated path.

**Numbers.** Owners other than `root` get user ids from **2000** in alphabetical order; every group name (and every
owner's own group, which has the owner's name) gets a group id from 2000 in alphabetical order. In the example above:
`guard2`=2000, `rami`=2001, `tech9`=2002; groups `guard2`=2000, `ops`=2001, `rami`=2002, `tech9`=2003. The same package
always installs the same numbers. Before the shell starts, matching lines are appended to `/etc/passwd` and
`/etc/group` (`rami:x:2001:2002:rami:/:/bin/sh`), so `ls -l`, `find -user rami`, `id rami` and `su rami` all work.

### Example package

```
rami-server.zip
├── devfest-manifest.csv
├── srv/rami/uploads/handover-0427.bak
├── srv/rami/logs/access.log
├── home/guard2/inbox/notes.txt
└── var/backups/db-dump.sql
```

## 4. How the terminal installs it

For the curious (and for debugging a package that "works on my machine"):

1. The page downloads the package from the server (`GET .../terminal-bundle`, sent with an ETag, so a restart asks
   "has it changed?" and usually receives `304 Not Modified` instead of the bytes).
2. It also downloads the **tools archive** (`/terminal/devfest-tools.tgz`, about 1.9 MB, the same file for every room;
   section 5). It is a static file of the site, not part of your package and not counted in its 20 MB.
3. After Linux has booted, the page puts the package, its accounts and the tools on the 9p share (`/mnt`) and types
   **one invisible command**: `gzip -dc /mnt/devfest-bundle.tgz | tar -xf - -C /` (this BusyBox `tar` has no `-z`),
   then appends the owners to `/etc/passwd` and `/etc/group`, unpacks the tools into `/usr/local` the same way, makes
   `/usr/local/bin` the first directory of the shell's `PATH`, sets the room's terminal clock if it has one (section 6),
   removes the setup files from `/mnt` and does `cd ~`.
4. Only then is the shell handed to the team; they never see the setup. If the package step fails, the team sees *"The
   room's server could not be set up in this terminal. Press Restart terminal, or ask an organizer."*; if the tools
   cannot be downloaded, *"The terminal's extra tools could not be downloaded. …"*; if they cannot be installed, *"The
   terminal's extra tools could not be installed. …"*. Never a half-built shell.

A room with a package uses **80 MB** of guest memory (every other terminal keeps 64 MB): the package and the tools live
in the guest's RAM, and the compiled `file` database alone is 10 MB. Measured on this laptop (headless Chromium, v86,
tools installed; "Ready in" is the terminal's own stopwatch from start to prompt):

| Package (three runs each) | Test terminal → prompt | `free -m` (total / used / free) after installing |
|---|---|---|
| 2000 files under `/home/server` (mixed types) | 13.9, 13.4, 13.3 s | 66 / 33 / 33 MB (all three runs) |
| one incompressible 20 MB file (the heaviest allowed) | 18.5, 17.4, 16.6 s | 66 / 44 / 21 to 22 MB |
| no package, no tools (the plain terminal, same session) | 8.7, 8.7, 7.7 s | — |

**Read the times with care.** These runs were taken while a game was using the laptop (CPU load 64 to 71 %), which
makes the emulated machine about 1.8 times slower than on an idle laptop; the plain terminal needs 7.7 to 8.7 s now and
needed about 4.4 to 4.8 s on the idle laptop. Single runs on the idle laptop gave 5.8 s (301 files), 6.7 s (2000 files)
and 8.5 s (20 MB) with the tools, against 4.4 to 4.8 s and 7.6 to 7.8 s for the same packages before the tools existed.
The memory figures do not depend on load. Measure again on the event laptop before the event.

## 5. Commands available in the terminal

The image is BusyBox **v1.28.4** with 265 applets, shell `sh` (ash), `PATH=/bin:/sbin:/usr/bin:/usr/sbin`, running as
`root`. Use **Test terminal** on the room board to try your challenge exactly as teams will see it.

**Extra tools (rooms with a server package only).** The terminal installs these into `/usr/local` before the shell
starts, and `/usr/local/bin` comes first in the `PATH`, so **`find` is GNU find** (and `xargs` is GNU xargs):

| Command | What it is |
|---|---|
| `find`, `xargs` | GNU findutils 4.10.0: `-printf`, `-newermt`, `-mtime`, `-user`, `-group`, `-size`, `-name`, `-regex`, `-exec ... {} +`, `-print0`, … |
| `file` | file 5.47 with its compiled magic database (`file -b`, `--mime-type`, …) |
| `base64` | the base64 applet of BusyBox 1.37 (static): encode, `-d` decode, `-w` wrapping |
| `bzip2`, `bunzip2`, `bzcat` | bzip2 1.0.8 (`-k`, `-c`, `-d`, `-1` … `-9`) |
| `zip` | Info-ZIP zip 3.0 (BusyBox's `unzip` reads what it writes) |

They are 32-bit x86 programs built against musl; each command is a small wrapper that starts the real program through
the musl loader, so nothing in `/lib` or `/etc` is touched. In a room **without** a package none of them exist and the
terminal behaves exactly as before (BusyBox `find` without `-printf`, and so on). Sources, versions and licenses are in
section 9.

**Files and text:** `cat` `head` `tail` `less` `more` `nl` `fold` `paste` `cut` `tr` `sort` `uniq` `wc` `sed` `awk`
`grep` `egrep` `fgrep` `diff` `cmp` `patch` `od` `hexdump` `xxd` `strings` `uuencode` `uudecode` `dos2unix` `unix2dos`
`vi` `hexedit` `tee` `xargs` `printf` `echo` `seq` `expr` `dc` `factor`

**Finding things:** `find` (GNU find with the extra tools; in a room without a package BusyBox `find`: `-name` `-iname`
`-type` `-user` `-group` `-size` `-mtime` `-newer` `-perm` `-maxdepth` `-exec ... {} +` and `\;`, **no `-printf`**),
`ls` (`-l` `-a` `-n` `-R`, `--full-time`), `which`, `du`, `df`, `readlink`, `realpath`, `basename`, `dirname`, `pwd`,
`lsof`, `fuser`, `date -r FILE`

**Permissions and people:** `chmod` `chown` `chgrp` `id` `whoami` `who` `su` (`su tech9 -c id` works) `login` `adduser` `addgroup` `deluser`
`delgroup` `passwd` `mkpasswd` `chattr` `lsattr`

**Archives and checksums:** `tar` (no `-z`/`-j`; use `gzip -dc f.tgz | tar -xf -`) `gzip` `gunzip` `zcat` `bunzip2` `bzcat`
`bzip2` and `zip` (extra tools) `unzip` `cpio` `xz` `unxz` `xzcat` `lzma` `lzcat` `md5sum` `sha1sum` `sha256sum` `sha512sum` `sha3sum` `cksum`

**Files and folders:** `cp` `mv` `rm` `mkdir` `rmdir` `touch` `ln` `mktemp` `dd` `truncate` `shred` `install` `sync`
`mkfifo` `mknod`

**System:** `ps` `top` `kill` `killall` `pidof` `free` `uptime` `date` `uname` `hostname` `dmesg` `env` `printenv` `sleep`
`time` `watch` `mount` `umount` `ifconfig` `ip` `netstat` (there is no network device, so these show nothing useful)

**Not available** (a challenge must not rely on them): `stat`, `tree`, `nano`, `whereis`, `locate`, `tac`, `rev`, `comm`,
`expand`, and in a room **without** a package also `file`, `base64`, `bzip2` and `zip`. Alternatives: `ls -l --full-time`
or `find -printf` instead of `stat`; `ls -lnR` for numeric owners; `xxd`, `hexdump` or `od` to look inside a file.

Do not rebuild the Linux image for a challenge; design with the commands above. If a command is truly needed, ask the
admin first: the image is a shared, versioned asset (`ATTRIBUTION.md`). The tools archive is the way to add commands;
see section 9.

## 6. The terminal clock

On the **Server package** card, **Terminal clock (UTC date and time)** sets the date every team's terminal starts at.
Empty means real time. With a clock, the invisible setup runs `date -s` before the shell is handed over, so
`find -mtime`, `find -newermt` and `date` give the same answer on the day of the event, a week earlier or a year later:
if your file is dated 2026-03-04 and the clock says 2026-03-14, `find -mtime 10` finds it whenever the team plays.

- The clock then **runs normally** from that moment; **Restart terminal** starts again from the same value.
- It is saved with the package, **without uploading it again**; replacing the package keeps it, removing the package
  removes it. The card shows the current value ("Terminal clock … UTC" or "Real time").
- Same rules as the package: only the room's manager and the admin, read-only once the final opens, and every change is
  audited. Teams receive it only through the same gated answer that carries their package.
- It needs a package (there is no setup to run without one). It sets the guest's wall clock in UTC; there is no time zone
  inside the terminal.

## 7. The team form

A room with the **Linux terminal activity** (admin: Content page) shows the team a form next to the terminal instead of
the usual technical-output and clue-answer boxes. Five fields: **PATH**, **USERNAME**, **FILE_SIZE**, **PATTERN**,
**Final clue**. There is no separate encrypted message: your target file holds one encrypted clue, and the team types
its decryption as the Final clue.

- **Save** stores what is on the screen, as often as the team likes; blanks are fine. **Final submit** (the team is asked
  first) locks the form for good. Everything is kept on the server, so a refresh or a terminal restart changes nothing.
- A team that saved something but did not submit is submitted automatically when the round ends (End round, removed
  time, or the next round). A team that saved nothing, or only blanks, is not.
- The final answer becomes the room's ordinary submission: its **technical output** is a summary of the five values
  (you judge it as usual), and the **Final clue** is checked against the clue key you set on the room board (case and
  spacing do not matter, punctuation does). A correct clue gives the team the room's clue; a blank or wrong one does not.
- Teams see only **Saved** or **Submitted**: never whether an answer is right, a score, or a key. The other four
  answers are scored on the organizer dashboard (next section), not at submission.

## 8. The organizer dashboard

Open it from the room board (**Organizer dashboard**) or from the admin board's warning. Only you and the admin can
open it. It has three parts:

1. **Answer keys.** One box for PATH, USERNAME, FILE_SIZE and PATTERN, **one accepted value per line** (for example
   both `/srv/rami/uploads` and a second folder). Save them any time before the final opens. A key left empty cannot be awarded automatically: the admin board warns, and you can still award
   that item by hand. The Final clue is checked against the **clue key** you set on the room board, as always.
   - PATH ignores one trailing slash (`/srv/x/` equals `/srv/x`); PATTERN ignores surrounding quotes (`"*.bak"` equals
     `*.bak`); FILE_SIZE ignores case (`1337C` equals `1337c`); USERNAME is case-sensitive.
2. **Teams, out of 15.** Every active team, in every round, with its status (Not started, Saved, Submitted), what it
   wrote with a tick or cross for each of the five items, its total and when it submitted. The table is sorted by total
   and refreshes every 5 seconds. Each item is worth 3 points; the fifth item (**Clue**) is worth 3 points when the
   final clue is right (checked against the clue key, as for collecting the clue). Scores are computed from the keys every time, so fixing a key or the clue key fixes the table.
3. **Overrides.** Under each item choose **Award** or **Deny** to decide it yourself (it is marked *Overridden* and the
   change is audited); choose **Automatic** to clear it. Overriding Clue changes the points only: it never
   gives a team the room's clue or takes it away (that stays with the clue check and your ordinary Accept/Reject on the
   submission). The 15 points are for this room; the event's ranking does not use them.

**Export CSV** downloads the table; **Test terminal** starts the room's terminal with the current package.

## 9. Designing a find-based challenge (example)

The Playwright fixture (`frontend/e2e/linux-room-fixture.ts`) builds the kind of package this room is designed for:
300 text files in six folders owned by three people, plus one binary file. Its four search parameters each return
**several** files on their own, and only the combination returns exactly one:

```
find /srv/rami/uploads -type f                      # PATH      -> several files
find /srv /home /var -user tech9 -type f            # USERNAME  -> several files
find /srv /home /var -type f -size 1337c            # FILE_SIZE -> several files
find /srv /home /var -type f -name "*.bak"          # PATTERN   -> several files
find /srv/rami/uploads -user tech9 -size 1337c -name "*.bak"   # exactly one file
```

The target file holds one encrypted clue (in the fixture `Encrypted clue: ZHVW WRZHU`, a shift by three of the clue key
`WEST TOWER`); the team decrypts it and enters the result as the **Final clue**. There is no separate message field.

Keep the decoys believable (same names, sizes and owners as the target in every way except one), and re-read the
whole listing with `ls -lnR` once through **Test terminal** before the event.

## 10. Where the extra tools come from

Official **Alpine Linux v3.24 x86** packages (32-bit, musl), unmodified, taken from `https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/`.
Nothing is compiled. `infrastructure/build-terminal-tools.py` builds `frontend/public/terminal/devfest-tools.tgz`
(1,886,271 bytes gzip, 13.2 MB unpacked, SHA-256
`f1b1bd7993acd44e98742c8a8397ea3ea61c799c073615cb3fdeb593ad653e63`, the same bytes on every rebuild).

| Package | Version | License | SHA-256 of the package file | Download |
|---|---|---|---|---|
| musl | 1.2.6-r3 | MIT | `9e875b4ad655f8314019075560c88f6b4eb05b7f5eb30f4ed038d4fc56f70da3` | [musl-1.2.6-r3.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/musl-1.2.6-r3.apk) |
| libbz2 | 1.0.8-r6 | bzip2-1.0.6 | `636ace113aaf002da7b28dfb9e99e0d04df466cc177ffa27f68c4e6ebbb4ce2c` | [libbz2-1.0.8-r6.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/libbz2-1.0.8-r6.apk) |
| bzip2 | 1.0.8-r6 | bzip2-1.0.6 | `8803cb3782ba0a89451a06a3050ca8deecd3222480664c51ba5b8456367d4948` | [bzip2-1.0.8-r6.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/bzip2-1.0.8-r6.apk) |
| libmagic | 5.47-r2 | BSD-2-Clause | `bc2f1db78a6f2c2959ecdc84bb2b36f7e841347ef67a050f311983d5357009ac` | [libmagic-5.47-r2.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/libmagic-5.47-r2.apk) |
| file | 5.47-r2 | BSD-2-Clause | `7bb5c6bc5b1be0a9d677314f70f8576c38fd00c67cce29d68dc19702538d7c2c` | [file-5.47-r2.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/file-5.47-r2.apk) |
| findutils | 4.10.0-r1 | GPL-3.0-or-later | `8a3227f462cdcac121850e252122fe43c7e0e5523846242d9af732afd3f0fb81` | [findutils-4.10.0-r1.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/findutils-4.10.0-r1.apk) |
| zip | 3.0-r13 | Info-ZIP | `546054e32b36c18c5020175af4a96d84783be72aac69d8a38bad4e1da0038ead` | [zip-3.0-r13.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/zip-3.0-r13.apk) |
| busybox-static | 1.37.0-r31 | GPL-2.0-only | `26d45cc895b3fa99ba7500d0860c3934a363e74e464eeb5fc623f08147c33cfd` | [busybox-static-1.37.0-r31.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/busybox-static-1.37.0-r31.apk) |

How the downloads are treated as untrusted until verified (`python infrastructure/build-terminal-tools.py --lock` does
it, and a normal build repeats the checks):

1. Alpine's signing key `alpine-devel@lists.alpinelinux.org-61666e3f.rsa.pub` is fetched from https://alpinelinux.org/keys/ and must have the
   SHA-256 `128d34d4aec39b0daedea8163cd8dc24dff36fd3d848630ab97eeb1d3084bbb3` (pinned in the script).
2. `APKINDEX.tar.gz` must carry a valid RSA-SHA1 signature of that key (checked with OpenSSL); its SHA-256 at locking
   time was `38327f9a43949eb03225632b8127cc77bce457bbeb423d4eafee59df5dcbc878`.
3. Each package's control section must match the SHA-1 listed in the verified index, and its data section the SHA-256 in
   its own control file; the package file's SHA-256 (table) is then pinned in `infrastructure/terminal-tools.lock.json`.
4. A build uses exactly the locked versions and refuses any file whose SHA-256 differs. Moving to newer versions is a
   deliberate `--lock` run followed by a build and a test.

`file` and `libmagic` (BSD-2-Clause), `bzip2` and `libbz2` (the bzip2 license), `musl` (MIT) and `zip` (Info-ZIP license)
are permissively licensed. **GNU findutils (GPL-3.0-or-later) and BusyBox (GPL-2.0-only)** come with their source: the
exact source archives are the ones Alpine builds from (`https://gitlab.alpinelinux.org/alpine/aports`, branch
`3.24-stable`, `main/findutils` and `main/busybox`, which name the upstream tarballs and their checksums), and the
programs run only inside the visitor's browser. The full license texts, each from its project's own site and pinned by
SHA-256, are in `frontend/public/terminal/TOOLS-LICENSES.txt`, next to `V86-LICENSE`.

Limits: archive 1.9 MB gzip (limit 6 MB, kept); free memory with a 20 MB package is 21 to 22 MB (limit 10 MB, kept;
this needed the 80 MB guest described in section 4, because with 64 MB only 6 MB were left). Boot growth (limit 2 s):
on the idle laptop the tools added about 1.0 to 1.4 s (one 301-file run, 4.4 to 4.8 s before, 5.8 s after); that was not
re-measured against a no-tools build under the later, loaded conditions, so treat the 2 s limit as met on an idle laptop
and unproven under load (section 4).
