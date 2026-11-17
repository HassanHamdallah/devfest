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
- The banner says: *Noor's server is mounted. There is no internet in this terminal.*
- **Look.** The Linux room's terminal looks like Ubuntu: an aubergine screen (`#300a24`) with light text, and the prompt
  `investigator@noor-server:<path>$ ` with the name in bold green (`#8ae234`) and the path in bold blue (`#729fcf`), `~` for
  the home directory. The prompt is set by the invisible setup of a room **with a server package** (a `PS1` line using
  BusyBox's `\w`, `\033` and `\[ \]`, so long commands wrap and Up/Down redraw correctly); the Linux image is not
  changed. The final's terminal keeps its own look.
- **Full screen.** The terminal header has a **Full screen** button (the terminal card, also in the manager's *Test
  terminal*). The terminal is fitted to the screen and told its new size (`stty cols … rows …`, invisibly, at an empty
  prompt); **Exit full screen** or Esc returns it to 80 x 24. The keyboard stays in the terminal. A browser that refuses
  full screen simply changes nothing.
- **Names on screen.** What people see as **FILE_NAME** (the former PATTERN) and **RETRIEVED KEY** (the former Final clue; on
  the manager's room board the key card is called *Retrieved key* in this room) are labels only: the stored fields,
  the JSON names (`pattern`, `finalClue`), the score items (`PATTERN`, `CLUE`) and the answer keys are unchanged, so earlier
  answers and keys keep scoring. The dashboard CSV columns are `fileName`, `retrievedKey`, `fileNamePoints`, `retrievedKeyPoints`.
- **The terminal is saved in the team's browser.** After the team types, the whole Linux VM (files, shell history,
  running state and the text on the screen) is saved to the browser's own storage (IndexedDB): 5 seconds after the last
  keystroke, at most once every 15 seconds, and at once when the tab goes to the background. A terminal nobody typed in
  is not saved again. There is one saved terminal per browser, replaced by every save. After a **page refresh** in a
  running round the terminal starts by itself and shows "Restoring your terminal…" (about 1.5 s, against 6–8 s for a
  normal boot); the team is back at the same screen with its files. The save belongs to the team, the room, the round
  and the exact package (a changed package or terminal clock is a different one): anything else is deleted, and if a
  restore fails the terminal boots normally with a short notice. **Restart terminal**, the end of the round and
  **signing out** delete the saved terminal. A save is about 70–85 MB (the guest's RAM, 80 MB with a package). If the
  browser refuses to store it (no space, a private window), the terminal keeps running and shows *"Your terminal cannot
  be saved in this browser. A page refresh will restart it."* Never an error dialog.

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
2. It also downloads the **tools archive** (`/terminal/devfest-tools.tgz`, about 4.7 MB, the same file for every room;
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
| `nano` | GNU nano 9.3: a full-screen editor (`nano file`; **Ctrl+O** saves, **Ctrl+X** exits, arrow keys move). It runs on the terminal's alternate screen, so the team gets back exactly the screen it had |
| `tree` | tree 2.3.2: draws a folder as a tree (`tree -L 2 /srv`, `-a`, `-d`, `-u`, `-p`) |
| `openssl` | OpenSSL 3.5.9, the original command: `openssl enc -d -aes-256-cbc -pbkdf2 -in f.enc -out f.txt -pass file:secret.txt`, `openssl pkeyutl -decrypt -inkey key.pem -in f.rsa -out secret.txt -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256`, `openssl dgst`, `openssl rand`, `openssl genrsa`, `openssl version`. Alpine's stock configuration (default provider only, no legacy algorithms such as MD4 or RC4). A `-pass file:` reads the **first line**, and a CR from a Windows-made file stays in the passphrase, so write secrets with a single LF. **The first random bytes:** the guest's kernel (4.16) has no credited entropy source (it predates `random.trust_cpu`, so the emulated RDRAND is not counted), so `getrandom()` blocks until the pool is credited: in a room terminal the **first** command that needs random bytes (an RSA decryption, `rand`, `genrsa`) waits about 100 to 115 s after the terminal started; after that it takes about a second. `openssl enc -d` with a given passphrase does not need it. **The final's terminal seeds the pool during its setup** (`haveged -F --once`, below), so there the first RSA decryption takes under a second. A terminal restored after a page refresh is already past it |

They are 32-bit x86 programs built against musl; each command is a small wrapper that starts the real program through
the musl loader, so nothing in `/lib` or `/etc` is touched. `nano` also needs a terminal description: its wrapper points
`TERMINFO` at the one entry the guest uses (`TERM=linux`), which is part of the archive, so nothing outside `/usr/local`
is needed there either. In a room **without** a package none of them exist and the
terminal behaves exactly as before (BusyBox `find` without `-printf`, and so on). Sources, versions and licenses are in
section 10.

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

**Not available** (a challenge must not rely on them): `stat`, `whereis`, `locate`, `tac`, `rev`, `comm`,
`expand`, and in a room **without** a package also `file`, `base64`, `bzip2`, `zip`, `nano`, `tree` and `openssl`. Alternatives: `ls -l --full-time`
or `find -printf` instead of `stat`; `ls -lnR` for numeric owners; `xxd`, `hexdump` or `od` to look inside a file.

Do not rebuild the Linux image for a challenge; design with the commands above. If a command is truly needed, ask the
admin first: the image is a shared, versioned asset (`ATTRIBUTION.md`). The tools archive is the way to add commands;
see section 10.

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
the usual technical-output and clue-answer boxes. Five fields: **PATH**, **USERNAME**, **FILE_SIZE**, **FILE_NAME**,
**RETRIEVED KEY**. There is no separate encrypted message: your target file holds one encrypted clue, and the team types
its decryption as the RETRIEVED KEY.

- **Save** stores what is on the screen, as often as the team likes; blanks are fine. **Final submit** (the team is asked
  first) locks the form for good. Everything is kept on the server, so a refresh or a terminal restart changes nothing.
- A team that saved something but did not submit is submitted automatically when the round ends (End round, removed
  time, or the next round). A team that saved nothing, or only blanks, is not.
- The final answer becomes the room's ordinary submission: its **technical output** is a summary of the five values
  (you judge it as usual), and the **RETRIEVED KEY** is checked against the retrieved key you set on the room board (case and
  spacing do not matter, punctuation does). A correct clue gives the team the room's clue; a blank or wrong one does not.
- Teams see only **Saved** or **Submitted**: never whether an answer is right, a score, or a key. The other four
  answers are scored on the organizer dashboard (next section), not at submission.

## 8. The organizer dashboard

Open it from the room board (**Organizer dashboard**) or from the admin board's warning. Only you and the admin can
open it. It has three parts:

1. **Answer keys.** One box for PATH, USERNAME, FILE_SIZE and FILE_NAME, **one accepted value per line** (for example
   both `/srv/rami/uploads` and a second folder). Save them any time before the final opens. A key left empty cannot be awarded automatically: the admin board warns, and you can still award
   that item by hand. The RETRIEVED KEY is checked against the **retrieved key** you set on the room board, as always.
   - PATH ignores one trailing slash (`/srv/x/` equals `/srv/x`); FILE_NAME ignores surrounding quotes (`"*.bak"` equals
     `*.bak`); FILE_SIZE ignores case (`1337C` equals `1337c`); USERNAME is case-sensitive.
2. **Teams, out of 15.** Every active team, in every round, with its status (Not started, Saved, Submitted), what it
   wrote with a tick or cross for each of the five items, its total and when it submitted. The table is sorted by total
   and refreshes every 5 seconds. Each item is worth 3 points; the fifth item (**RETRIEVED KEY**) is worth 3 points when the
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
find /srv /home /var -type f -name "*.bak"          # FILE_NAME   -> several files
find /srv/rami/uploads -user tech9 -size 1337c -name "*.bak"   # exactly one file
```

The target file holds one encrypted clue (in the fixture `Encrypted clue: ZHVW WRZHU`, a shift by three of the clue key
`WEST TOWER`); the team decrypts it and enters the result as the **RETRIEVED KEY**. There is no separate message field.

Keep the decoys believable (same names, sizes and owners as the target in every way except one), and re-read the
whole listing with `ls -lnR` once through **Test terminal** before the event.

## 10. Where the extra tools come from

Official **Alpine Linux v3.24 x86** packages (32-bit, musl), unmodified, taken from `https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/`.
Nothing is compiled. `infrastructure/build-terminal-tools.py` builds `frontend/public/terminal/devfest-tools.tgz`
(4,723,997 bytes gzip, 18.5 MB unpacked, SHA-256
`3b9e1bf9cf249bddc3825b8b6062bf95e90e05d49a2599fea0444119e0f322d2`, the same bytes on every rebuild).

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
| libncursesw | 6.6_p20260516-r0 | X11 | `49a048c29a93046b3fc5e72a21bb55ef537558d026f1064a42d25f42f19a90e9` | [libncursesw-6.6_p20260516-r0.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/libncursesw-6.6_p20260516-r0.apk) |
| ncurses-terminfo-base | 6.6_p20260516-r0 | X11 | `dbb8268b7a8c9e98b109dc6bd1a989f846bd49b71a186308a2224f3195620501` | [ncurses-terminfo-base-6.6_p20260516-r0.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/ncurses-terminfo-base-6.6_p20260516-r0.apk) |
| nano | 9.3-r0 | GPL-3.0-or-later | `22f9e80f856b64073dd9a77b30016e2bbd6c3f79ab956f1701cd0294ce6bf737` | [nano-9.3-r0.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/nano-9.3-r0.apk) |
| tree | 2.3.2-r0 | GPL-2.0-or-later | `0259e3aa65fd275f0a681ceef37e212af1d34c59f877f8af6d6737d17901ea49` | [tree-2.3.2-r0.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/tree-2.3.2-r0.apk) |
| libcrypto3 | 3.5.9-r0 | Apache-2.0 | `fefdfbd558fb994eb32d1f20fdad9791efd9021312f5183163e29b8d9fc940b2` | [libcrypto3-3.5.9-r0.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/libcrypto3-3.5.9-r0.apk) |
| libssl3 | 3.5.9-r0 | Apache-2.0 | `07371c13c19e5c7cdcc5b6e9476dab3f26d565ca8e3dc5d598b2ee3411e81d2f` | [libssl3-3.5.9-r0.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/libssl3-3.5.9-r0.apk) |
| openssl | 3.5.9-r0 | Apache-2.0 | `dc73b57886cd6565ca1753c6fc23bafab12e93d9c2cbd518265e68c195e398a9` | [openssl-3.5.9-r0.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/openssl-3.5.9-r0.apk) |
| haveged | 1.9.21-r0 | GPL-3.0-or-later | `f1f4c2a11c22df8f9d8965e686e618faae04861e744d8bfeedbca691e44751ee` | [haveged-1.9.21-r0.apk](https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/haveged-1.9.21-r0.apk) |

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

`file` and `libmagic` (BSD-2-Clause), `bzip2` and `libbz2` (the bzip2 license), `musl` (MIT), `zip` (Info-ZIP license),
and `libncursesw` with its terminfo entry (the ncurses X11 license) and OpenSSL 3 (`openssl`, `libcrypto3`, `libssl3`: Apache-2.0) are permissively licensed. **GNU findutils and GNU nano
(GPL-3.0-or-later), tree (GPL-2.0-or-later) and BusyBox (GPL-2.0-only)** come with their source: the
exact source archives are the ones Alpine builds from (`https://gitlab.alpinelinux.org/alpine/aports`, branch
`3.24-stable`, `main/findutils`, `main/nano`, `main/tree` and `main/busybox`, which name the upstream tarballs and their checksums), and the
programs run only inside the visitor's browser. The full license texts, each from its project's own site and pinned by
SHA-256, are in `frontend/public/terminal/TOOLS-LICENSES.txt`, next to `V86-LICENSE`.

OpenSSL: the wrapper sets `OPENSSL_CONF=/usr/local/devfest-tools/share/openssl.cnf`, Alpine's stock `/etc/ssl/openssl.cnf`
(default provider only), because the image has no `/etc/ssl`. Alpine's legacy module and engines are not in the archive.
Adding OpenSSL (`openssl`, `libcrypto3`, `libssl3`) grew the archive by 2,458,805 bytes gzip (2,236,342 to 4,695,147) and the
unpacked size by 5.4 MB (13.9 to 19.3 MB); the build added no other change to the 12 earlier packages (same versions and SHA-256).
Boot time of the Linux room (test terminal, 301-file package, headless Edge, one warm-up boot then three measured): before 5.5, 5.4, 5.4 s
(mean 5.43 s); after 5.6, 5.9, 6.0 s (mean 5.83 s), so OpenSSL costs about 0.4 s. Free memory in the 80 MB guest after installing the
tools with a small package: 36 MB.

haveged (the final's entropy seed): the final's setup runs `haveged -F --once -b 16` after the tools are installed and before
the ready mark. haveged runs its own online tests (AIS-31 A and B, both passed in the guest), credits 4 KB to the kernel with
RNDADDENTROPY ("crng init done" follows) and exits; nothing keeps running. Added with `--add haveged`, which verifies one package
against the signed index and leaves the 15 locked ones as they were; it grew the archive by 28,850 bytes gzip (haveged and
libhavege.so.2). Measured with the real image in v86 (Node, the final package, 3 boots each): **before** "ready" 5.3, 5.1, 5.0 s and
the first `openssl pkeyutl -decrypt` 113.7, 115.8, 115.8 s; **after** "ready" 6.4, 6.6, 6.6 s and the first RSA decryption 0.7, 0.6,
0.7 s. It costs about 1.4 s of setup (a smaller buffer, `-b 4`, did not make it cheaper: most of it is haveged's start-up and
tests), more than the 1 s a Linux room may spend, so only the final's terminal runs it (`seedEntropy` on the terminal component).
A failing haveged is not fatal: the shell still starts and only the first RSA decryption is slow again.

Terminfo: only `l/linux` is bundled, because the guest's `TERM` is `linux` (checked in the real terminal); the wrapper sets
`TERMINFO=/usr/local/devfest-tools/share/terminfo`. Adding `nano`, `tree`, libncursesw and that entry grew the archive by
350,071 bytes gzip (1,886,271 to 2,236,342) and the unpacked size by about 0.7 MB.

Limits: archive 4.7 MB gzip with OpenSSL (limit 6 MB, kept; it was 2.2 MB before); free memory with a 20 MB package is 16 MB with OpenSSL (21 to 22 MB before; limit 10 MB, kept;
this needed the 80 MB guest described in section 4, because with 64 MB only 6 MB were left). Boot growth (limit 2 s):
on the idle laptop the tools added about 1.0 to 1.4 s (one 301-file run, 4.4 to 4.8 s before, 5.8 s after); that was not
re-measured against a no-tools build under the later, loaded conditions, so treat the 2 s limit as met on an idle laptop
and unproven under load (section 4).

Boot time with the four added packages (test terminal, 301-file package, headless Edge, the terminal's own "Ready in",
one warm-up boot then three measured): before 5.4, 5.1, 5.1 s (mean 5.20 s); after 5.1, 5.1, 5.3 s and 5.3, 5.2, 5.1 s
(means 5.17 and 5.20 s), and 5.2, 4.9, 5.3 s (5.13 s) with the final archive. No measurable growth (limit 1 s).
