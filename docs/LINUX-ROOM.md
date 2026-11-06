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
the ZIP entries (so `ls -l` and `find -mtime` show the dates you saved; DOS times are read as UTC).

**Folders.** A folder is written into the archive only when a manifest rule covers it **or** it is empty (an empty
folder entry in the ZIP is kept). Other parent folders are simply created as needed, so a package never changes the
owner or mode of a folder the image already has (for example `/tmp`).

A symbolic link inside a ZIP is installed as a plain file containing the target path; the archive format used here
carries no links.

If anything is wrong, **every problem is listed at once** and nothing is stored. Replacing a package is audited (SHA-256
and counts only, never file content), and like all content the package is read-only once the final opens.

## 3. The optional manifest: `devfest-manifest.csv`

A file with exactly this name in the **root** of the ZIP sets owners, groups and modes. It is **not installed**.

```
path,owner,group,mode
/srv/rami/**,rami,ops,0640
/srv/rami/uploads/handover-0427.bak,tech9,tech9,0644
/home/guard2/**,guard2,guard2,0600
```

- One rule per line: `path,owner,group,mode`. The header line, blank lines and lines starting with `#` are optional /
  ignored. Wrap a field in double quotes if it contains a comma. The leading `/` of a path is optional.
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
2. After Linux has booted, the page puts the package on the 9p share (`/mnt`) and types **one invisible command**:
   `gzip -dc /mnt/devfest-bundle.tgz | tar -xf - -C /` (this BusyBox `tar` has no `-z`), then appends the owners to
   `/etc/passwd` and `/etc/group`, removes the setup files from `/mnt` and does `cd ~`.
3. Only then is the shell handed to the team; they never see the setup. If any step fails, the team sees *"The room's
   server could not be set up in this terminal. Press Restart terminal, or ask an organizer."* instead of a half-built
   shell.

Measured on this laptop (headless Edge, v86, **64 MB** guest memory, which stays unchanged):

| Package | Test terminal → prompt | `free -m` (total / used / free) after installing |
|---|---|---|
| 301 files (the Playwright fixture) | 5.0 s | 50 / 13 / 37 MB |
| one incompressible 20 MB file (the heaviest allowed) | 8.1 s | 50 / 32 / 18 MB |

## 5. Commands available in the terminal

The image is BusyBox **v1.28.4** with 265 applets, shell `sh` (ash), `PATH=/bin:/sbin:/usr/bin:/usr/sbin`, running as
`root`. Use **Test terminal** on the room board to try your challenge exactly as teams will see it.

**Files and text:** `cat` `head` `tail` `less` `more` `nl` `fold` `paste` `cut` `tr` `sort` `uniq` `wc` `sed` `awk`
`grep` `egrep` `fgrep` `diff` `cmp` `patch` `od` `hexdump` `xxd` `strings` `uuencode` `uudecode` `dos2unix` `unix2dos`
`vi` `hexedit` `tee` `xargs` `printf` `echo` `seq` `expr` `dc` `factor`

**Finding things:** `find` (verified options: `-name` `-iname` `-type` `-user` `-group` `-size` `-mtime` `-newer`
`-perm` `-maxdepth` `-exec ... {} +` and `\;`; **no `-printf`**), `ls` (`-l` `-a` `-n` `-R`, `--full-time`), `which`, `du`,
`df`, `readlink`, `realpath`, `basename`, `dirname`, `pwd`, `lsof`, `fuser`, `date -r FILE`

**Permissions and people:** `chmod` `chown` `chgrp` `id` `whoami` `who` `su` (`su tech9 -c id` works) `login` `adduser` `addgroup` `deluser`
`delgroup` `passwd` `mkpasswd` `chattr` `lsattr`

**Archives and checksums:** `tar` (no `-z`/`-j`; use `gzip -dc f.tgz | tar -xf -`) `gzip` `gunzip` `zcat` `bunzip2` `bzcat`
`unzip` `cpio` `xz` `unxz` `xzcat` `lzma` `lzcat` `md5sum` `sha1sum` `sha256sum` `sha512sum` `sha3sum` `cksum`

**Files and folders:** `cp` `mv` `rm` `mkdir` `rmdir` `touch` `ln` `mktemp` `dd` `truncate` `shred` `install` `sync`
`mkfifo` `mknod`

**System:** `ps` `top` `kill` `killall` `pidof` `free` `uptime` `date` `uname` `hostname` `dmesg` `env` `printenv` `sleep`
`time` `watch` `mount` `umount` `ifconfig` `ip` `netstat` (there is no network device, so these show nothing useful)

**Not available** (a challenge must not rely on them): `file`, `stat`, `tree`, `base64`, `nano`, and also `whereis`,
`locate`, `tac`, `rev`, `comm`, `expand`, `bzip2` and `zip` (`bunzip2` and `unzip` exist). Alternatives: `ls -l --full-time`
or `date -r FILE` instead of `stat`; `ls -lnR` for numeric owners; `xxd`, `hexdump` or `od` to look inside a file;
`strings` instead of `file`.

Do not rebuild the Linux image for a challenge; design with the commands above. If a command is truly needed, ask the
admin first: the image is a shared, versioned asset (`ATTRIBUTION.md`).

## 6. The team form

A room with the **Linux terminal activity** (admin: Content page) shows the team a form next to the terminal instead of
the usual technical-output and clue-answer boxes. Six fields: **PATH**, **USERNAME**, **FILE_SIZE**, **PATTERN**,
**Encrypted message**, **Final clue**.

- **Save** stores what is on the screen, as often as the team likes; blanks are fine. **Final submit** (the team is asked
  first) locks the form for good. Everything is kept on the server, so a refresh or a terminal restart changes nothing.
- A team that saved something but did not submit is submitted automatically when the round ends (End round, removed
  time, or the next round). A team that saved nothing, or only blanks, is not.
- The final answer becomes the room's ordinary submission: its **technical output** is a summary of the six values
  (you judge it as usual), and the **Final clue** is checked against the clue key you set on the room board (case and
  spacing do not matter, punctuation does). A correct clue gives the team the room's clue; a blank or wrong one does not.
- Teams see only **Saved** or **Submitted**: never whether an answer is right, a score, or a key. The other five
  answers are scored on the organizer dashboard (next section), not at submission.

## 7. The organizer dashboard

Open it from the room board (**Organizer dashboard**) or from the admin board's warning. Only you and the admin can
open it. It has three parts:

1. **Answer keys.** One box for PATH, USERNAME, FILE_SIZE and PATTERN, **one accepted value per line** (for example
   both `/srv/rami/uploads` and a second folder), and one value for the encrypted message. Save them any time before
   the final opens. A key left empty cannot be awarded automatically: the admin board warns, and you can still award
   that item by hand. The Final clue is checked against the **clue key** you set on the room board, as always.
   - PATH ignores one trailing slash (`/srv/x/` equals `/srv/x`); PATTERN ignores surrounding quotes (`"*.bak"` equals
     `*.bak`); FILE_SIZE ignores case (`1337C` equals `1337c`); USERNAME is case-sensitive; the message ignores extra
     spaces and line breaks but not case or punctuation.
2. **Teams, out of 15.** Every active team, in every round, with its status (Not started, Saved, Submitted), what it
   wrote with a tick or cross for each of the five items, its total and when it submitted. The table is sorted by total
   and refreshes every 5 seconds. Each item is worth 3 points; the fifth item (**Message + clue**) is worth 3 points only
   when both are right. Scores are computed from the keys every time, so fixing a key or the clue key fixes the table.
3. **Overrides.** Under each item choose **Award** or **Deny** to decide it yourself (it is marked *Overridden* and the
   change is audited); choose **Automatic** to clear it. Overriding Message + clue changes the points only: it never
   gives a team the room's clue or takes it away (that stays with the clue check and your ordinary Accept/Reject on the
   submission). The 15 points are for this room; the event's ranking does not use them.

**Export CSV** downloads the table; **Test terminal** starts the room's terminal with the current package.

## 8. Designing a find-based challenge (example)

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

Keep the decoys believable (same names, sizes and owners as the target in every way except one), and re-read the
whole listing with `ls -lnR` once through **Test terminal** before the event.
