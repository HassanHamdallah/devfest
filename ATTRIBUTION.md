# Attribution

DevFest adapts the privately supplied TeamQora project (https://github.com/waseemjawdat1/teamqora). Original authors retain their rights. No root LICENSE file was found; no new license is asserted over their work. Obtain the authors' permission before distributing reused material.

Copied: Maven wrapper scripts and their Apache notices, Angular tsconfig files, shared ui-button/spinner/input/select/textarea/card/alert/badge/empty-state/pagination components, design tokens and base styles. Authentication, authorization, validation and error-handling patterns are adapted with new contest-specific implementation. No original comments/notices were intentionally removed from copied components.

The event UI's inline SVG icons (core/shell/nav-icon.ts, copy and close glyphs) follow the outline geometry of Feather Icons (https://feathericons.com, MIT, © Cole Bemis); no icon package is installed. The GDG On Campus · Birzeit University logo in frontend/public/brand belongs to its chapter and is used here for the event.

## In-browser terminal (frontend/public/terminal, lazy-loaded)

The Linux room terminal runs entirely in the browser. These files are self-hosted (no CDN at runtime). The `.gz`
files are `gzip -9` copies made by `tools/gzip-terminal-assets.mjs`; the page inflates them, and the raw SHA-256 is
the hash of the inflated file (identical to the upstream download).

| File | Component, version | Source | License | Raw bytes · SHA-256 | Stored file bytes · SHA-256 |
|---|---|---|---|---|---|
| `libv86.js` | v86 0.5.471 (npm `v86@0.5.471`, build `+g94faf3d`) | https://www.npmjs.com/package/v86 (`build/libv86.js`), https://github.com/copy/v86 | BSD-2-Clause (`V86-LICENSE`) | 359839 · `1b41074f7a5604f528dac6842baf7afccd79d7370b97ea3241944c275b67bec1` | same file |
| `v86.wasm.gz` | v86 0.5.471 (`build/v86.wasm`) | same npm package | BSD-2-Clause | 2007347 · `2fc5609ae837fcbc77147155de6f1014677ecab5369f5584ebc670e9d56e6510` | 381728 · `2daf3006847e009bbd4232819d9f7175f6f6d4463c8a109b097321b3befa41ff` |
| `seabios.bin.gz` | SeaBIOS 1.16.2 as built by v86 (`bios/seabios.bin`, commit b8a39b11dd2076870699e6cac053556271b9bfab, `bios/fetch-and-build-seabios.sh`) | https://github.com/copy/v86/tree/master/bios ; SeaBIOS source https://www.seabios.org/ (git https://review.coreboot.org/seabios.git, tag rel-1.16.2) | LGPL-3.0 (`SEABIOS-COPYING.LESSER`) | 131072 · `73e3f359102e3a9982c35fce98eb7cd08f18303ac7f1ba6ebfbe6cdc1c244d98` | 67327 · `61a4cac20b017c3b096f12a91658a2c8d932d05089c01db7eab84c28136027f6` |
| `vgabios.bin.gz` | SeaVGABIOS 1.16.2 as built by v86 (`bios/vgabios.bin`, same commit) | same as above | LGPL-3.0 | 36352 · `a4bc0d80cc3ca028c73dafa8fee396b8d054ce87ebd8abfbd31b06b437607880` | 16345 · `81f48113c09535b836aeaf056605f773d9a8ffeff2df935b1019e26c58f48892` |
| `linux4.iso.gz` | Linux image of the v86 project: Buildroot 2018.08-git-00153-g8ac477681f, Linux 4.16.13 (i686), BusyBox 1.28.4 | https://i.copy.sh/linux4.iso (listed in the v86 README) | GPL-2.0 (Linux, BusyBox) and the licenses of the other Buildroot packages | 7731200 · `a8ea434ab3b177c55f01275dcc1d35f52cfbee9bd44a32e74765c975b58bcc73` | 7179525 · `b3f20f455b86146c6cf882dd73908408c652d725bea859500270fd722015bc8b` |
| `devfest-tools.tgz` | Extra command line tools of rooms with a server package, assembled unmodified from official Alpine Linux v3.24 x86 packages by `infrastructure/build-terminal-tools.py`: musl 1.2.6, bzip2/libbz2 1.0.8, file/libmagic 5.47, GNU findutils 4.10.0, Info-ZIP zip 3.0, BusyBox 1.37.0 (static, for `base64`), GNU nano 9.3, tree 2.3.2, ncurses 6.6 (libncursesw and its `linux` terminfo entry) | https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/ (exact files, versions and SHA-256 in `infrastructure/terminal-tools.lock.json` and docs/LINUX-ROOM.md section 10); sources via https://gitlab.alpinelinux.org/alpine/aports | MIT (musl), BSD-2-Clause (file, libmagic), bzip2 license, Info-ZIP license, GPL-3.0-or-later (findutils, nano), GPL-2.0-or-later (tree), GPL-2.0-only (BusyBox), X11 (ncurses); texts in `TOOLS-LICENSES.txt` | 13878599 unpacked · `82aa6cd7b8e657fcbceb59b0483624ddcbe2810ee43aed1d65e71a70e7bcaf8c` | 2236342 · same file |
| xterm.js (bundled into the lazy `linux-terminal` chunk, CSS included) | `@xterm/xterm` 6.0.0 | https://www.npmjs.com/package/@xterm/xterm | MIT | — | — |

**GPL notice for the Linux image.** `linux4.iso` contains the Linux kernel 4.16.13 and BusyBox 1.28.4, both licensed
under the GNU General Public License version 2, built with Buildroot 2018.08 by the v86 project (it is distributed
here unmodified, byte-identical to the upstream file above). The corresponding source code is available from the
upstream projects: Linux 4.16.13 at https://cdn.kernel.org/pub/linux/kernel/v4.x/linux-4.16.13.tar.xz, BusyBox
1.28.4 at https://busybox.net/downloads/busybox-1.28.4.tar.bz2, Buildroot at https://buildroot.org/downloads/ (git
https://gitlab.com/buildroot.org/buildroot, commit 8ac477681f); the image and its build come from the v86 project
(https://github.com/copy/v86). Whoever serves this image to teams distributes GPL software and must be able to supply
the corresponding source on request (GPL-2.0 §3); keeping copies of the source archives above with the event
materials covers this. (Not legal advice; the organizers should confirm.)

Third-party packages retain their package licenses. v86 is BSD-2-Clause and xterm.js MIT (see the terminal table
above); Monaco is MIT; Judge0 CE is GPL-3.0 and is an external service, not vendored. Angular build license extraction remains enabled. No font package is bundled; the UI uses system fonts.
