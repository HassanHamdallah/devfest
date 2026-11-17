#!/usr/bin/env python3
"""Builds the Linux room terminal's extra tools archive (frontend/public/terminal/devfest-tools.tgz).

The terminal's Linux image is BusyBox without file, base64, bzip2, zip, a full find, nano, tree or openssl (nor haveged, which
the final's terminal runs once at setup to seed the kernel's random pool). This script assembles them from
official Alpine Linux x86 (32-bit) packages, which are built against musl, so the archive also carries the musl loader.
Nothing is compiled and nothing is taken on trust:

  * the package index (APKINDEX.tar.gz) is verified with Alpine's RSA signing key (RSA-SHA1 over its data stream, the
    key itself pinned by SHA-256 below and fetched from alpinelinux.org over HTTPS);
  * every package's control section must match the SHA-1 the verified index lists, and its data section the SHA-256
    in its own control file; the package file's SHA-256 is recorded in the lock file;
  * license texts come from the projects' own sites and are pinned by SHA-256 as well.

Modes:
  python infrastructure/build-terminal-tools.py --lock    resolve the packages from the live index, verify them and
                                                          write infrastructure/terminal-tools.lock.json (do this when
                                                          deliberately moving to newer versions)
  python infrastructure/build-terminal-tools.py --add N  verify one more package N against the live signed index and add it
                                                          to the lock, leaving every locked package as it is
  python infrastructure/build-terminal-tools.py           rebuild the archive from the lock (exact versions, verified
                                                          again); the result is byte-identical for the same inputs

Layout inside the archive (extracted from "/" by the terminal's setup; nothing outside /usr/local is touched):
  /usr/local/bin/<tool>                 small sh wrappers (first in PATH, so "find" is GNU find)
  /usr/local/devfest-tools/elf/         the real binaries
  /usr/local/devfest-tools/lib/         the musl loader and libbz2, libmagic, libncursesw, libcrypto, libssl, libhavege
  /usr/local/devfest-tools/share/       the compiled magic database for file; terminfo/l/linux for nano (TERM is "linux");
                                        openssl.cnf for OpenSSL (the wrapper sets OPENSSL_CONF)
Each wrapper runs its binary through the musl loader with an explicit library path, so no file in /lib or /etc is
needed.
"""
import argparse
import base64
import gzip
import hashlib
import io
import json
import re
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIRROR = "https://dl-cdn.alpinelinux.org/alpine"
BRANCH, REPOSITORY, ARCH = "v3.24", "main", "x86"
KEY_NAME = "alpine-devel@lists.alpinelinux.org-61666e3f.rsa.pub"
KEY_URL = "https://alpinelinux.org/keys/" + KEY_NAME
KEY_SHA256 = "128d34d4aec39b0daedea8163cd8dc24dff36fd3d848630ab97eeb1d3084bbb3"
PACKAGES = ["musl", "libbz2", "bzip2", "libmagic", "file", "findutils", "zip", "busybox-static", "libncursesw", "ncurses-terminfo-base", "nano", "tree", "libcrypto3", "libssl3", "openssl", "haveged"]
LOCK = ROOT / "infrastructure" / "terminal-tools.lock.json"
CACHE = ROOT / ".local" / "terminal-tools-cache"
ARCHIVE = ROOT / "frontend" / "public" / "terminal" / "devfest-tools.tgz"
LICENSE_FILE = ROOT / "frontend" / "public" / "terminal" / "TOOLS-LICENSES.txt"
VERSION_FILE = ROOT / "frontend" / "src" / "app" / "features" / "event" / "terminal" / "tools-version.ts"
EPOCH = 1_767_225_600  # 2026-01-01T00:00:00Z: the one modification time of every archive entry

BASE = "usr/local/devfest-tools"
LOADER = f"/{BASE}/lib/ld-musl-i386.so.1"
LIBRARIES = f"/{BASE}/lib"

#  archive path -> (package, path inside the package's data)
FILES = {
    f"{BASE}/lib/ld-musl-i386.so.1": ("musl", "lib/ld-musl-i386.so.1"),
    f"{BASE}/lib/libbz2.so.1.0.8": ("libbz2", "usr/lib/libbz2.so.1.0.8"),
    f"{BASE}/lib/libmagic.so.1.0.0": ("libmagic", "usr/lib/libmagic.so.1.0.0"),
    f"{BASE}/lib/libncursesw.so.6.6": ("libncursesw", "usr/lib/libncursesw.so.6.6"),
    f"{BASE}/lib/libcrypto.so.3": ("libcrypto3", "usr/lib/libcrypto.so.3"),
    f"{BASE}/lib/libssl.so.3": ("libssl3", "usr/lib/libssl.so.3"),
    f"{BASE}/lib/libhavege.so.2": ("haveged", "usr/lib/libhavege.so.2.0.0"),
    # Alpine's stock configuration (it only loads the default provider); the legacy module and the engines are left out.
    f"{BASE}/share/openssl.cnf": ("libcrypto3", "etc/ssl/openssl.cnf"),
    f"{BASE}/share/magic.mgc": ("libmagic", "usr/share/misc/magic.mgc"),
    # Only the entry the terminal uses: the guest's TERM is "linux" (checked in the real terminal).
    f"{BASE}/share/terminfo/l/linux": ("ncurses-terminfo-base", "etc/terminfo/l/linux"),
    f"{BASE}/elf/bzip2": ("bzip2", "usr/bin/bzip2"),
    f"{BASE}/elf/file": ("file", "usr/bin/file"),
    f"{BASE}/elf/find": ("findutils", "usr/bin/find"),
    f"{BASE}/elf/xargs": ("findutils", "usr/bin/xargs"),
    f"{BASE}/elf/zip": ("zip", "usr/bin/zip"),
    f"{BASE}/elf/nano": ("nano", "usr/bin/nano"),
    f"{BASE}/elf/tree": ("tree", "usr/bin/tree"),
    f"{BASE}/elf/openssl": ("openssl", "usr/bin/openssl"),
    # The guest's kernel (4.16) has no credited entropy source, so getrandom() blocks for about 100 s after boot; the
    # final's setup runs "haveged -F --once" to credit the pool before the shell is handed over.
    f"{BASE}/elf/haveged": ("haveged", "usr/sbin/haveged"),
    f"{BASE}/elf/busybox.static": ("busybox-static", "bin/busybox.static"),
}
SYMLINKS = {
    f"{BASE}/lib/libc.musl-x86.so.1": "ld-musl-i386.so.1",
    f"{BASE}/lib/libbz2.so.1": "libbz2.so.1.0.8",
    f"{BASE}/lib/libmagic.so.1": "libmagic.so.1.0.0",
    f"{BASE}/lib/libncursesw.so.6": "libncursesw.so.6.6",
    f"{BASE}/elf/bunzip2": "bzip2",
    f"{BASE}/elf/bzcat": "bzip2",
}
#  command -> (binary name in elf/, extra arguments, environment line)
COMMANDS = {
    "find": ("find", "", ""),
    "xargs": ("xargs", "", ""),
    "file": ("file", "", f'export MAGIC=/{BASE}/share/magic.mgc\n'),
    "bzip2": ("bzip2", "", ""),
    "bunzip2": ("bunzip2", "", ""),
    "bzcat": ("bzcat", "", ""),
    "zip": ("zip", "", ""),
    "base64": ("busybox.static", " base64", ""),
    # TERMINFO makes ncurses read the one bundled entry, so nothing outside /usr/local is needed.
    "nano": ("nano", "", f'export TERMINFO=/{BASE}/share/terminfo\n'),
    "tree": ("tree", "", ""),
    # OPENSSL_CONF makes OpenSSL read the one bundled configuration instead of /etc/ssl, which the image does not have.
    "openssl": ("openssl", "", f'export OPENSSL_CONF=/{BASE}/share/openssl.cnf\n'),
    "haveged": ("haveged", "", ""),
}

#  licenses: key -> (title, url, applies to)
LICENSE_TEXTS = {
    "GPL-3.0": ("GNU General Public License, version 3", "https://www.gnu.org/licenses/gpl-3.0.txt", "findutils (GNU find and xargs), nano, haveged"),
    "GPL-2.0": ("GNU General Public License, version 2", "https://www.gnu.org/licenses/gpl-2.0.txt", "BusyBox (base64), tree"),
    "MIT": ("musl libc license (MIT)", "https://git.musl-libc.org/cgit/musl/plain/COPYRIGHT", "musl (the loader and libc)"),
    "BSD-2-Clause": ("file and libmagic license (BSD-2-Clause)", "https://raw.githubusercontent.com/file/file/master/COPYING", "file, libmagic"),
    "X11": ("ncurses license (X11)", "https://raw.githubusercontent.com/mirror/ncurses/master/COPYING", "libncursesw, the terminfo entry"),
    "Apache-2.0": ("Apache License, version 2.0 (OpenSSL 3)", "https://www.apache.org/licenses/LICENSE-2.0.txt", "openssl, libcrypto, libssl"),
    "bzip2": ("bzip2 license", "https://sourceware.org/git/?p=bzip2.git;a=blob_plain;f=LICENSE;hb=HEAD", "bzip2, libbz2"),
}
INFOZIP_SOURCE = "https://downloads.sourceforge.net/project/infozip/Zip%203.x%20%28latest%29/3.0/zip30.tar.gz"


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "devfest-terminal-tools-build"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def gzip_members(data):
    """The byte ranges of the concatenated gzip streams of an .apk or APKINDEX.tar.gz."""
    members, rest = [], data
    while rest:
        decompressor = zlib.decompressobj(wbits=31)
        decompressor.decompress(rest)
        used = len(rest) - len(decompressor.unused_data)
        members.append(rest[:used])
        rest = decompressor.unused_data
    return members


def untar(member):
    return tarfile.open(fileobj=io.BytesIO(gzip.decompress(member)))


def verify_index(data, key):
    """Alpine's RSA-SHA1 signature over the index's data stream; raises unless openssl says Verified OK."""
    members = gzip_members(data)
    if len(members) != 2:
        raise SystemExit("APKINDEX.tar.gz does not have a signature and a data stream.")
    signature_tar = untar(members[0])
    names = signature_tar.getnames()
    if names != [".SIGN.RSA." + KEY_NAME]:
        raise SystemExit(f"The index is signed by {names}, not by the pinned key {KEY_NAME}.")
    signature = signature_tar.extractfile(names[0]).read()
    with tempfile.TemporaryDirectory() as folder:
        folder = Path(folder)
        (folder / "key.pub").write_bytes(key)
        (folder / "signature").write_bytes(signature)
        (folder / "signed").write_bytes(members[1])
        result = subprocess.run(
            ["openssl", "dgst", "-sha1", "-verify", str(folder / "key.pub"), "-signature", str(folder / "signature"), str(folder / "signed")],
            capture_output=True,
            text=True,
        )
    if "Verified OK" not in result.stdout:
        raise SystemExit("The package index signature is NOT valid: " + result.stdout + result.stderr)
    index = untar(members[1]).extractfile("APKINDEX").read().decode("utf-8")
    return parse_index(index)


def parse_index(text):
    packages = {}
    for block in text.split("\n\n"):
        fields = {}
        for line in block.split("\n"):
            if len(line) > 2 and line[1] == ":":
                fields[line[0]] = line[2:]
        if "P" in fields:
            packages[fields["P"]] = fields
    return packages


def verified_package(name, apk, index_entry):
    """Checks a downloaded .apk against the verified index (control SHA-1) and its own data hash (SHA-256)."""
    members = gzip_members(apk)
    if len(members) != 3:
        raise SystemExit(f"{name}: expected a signature, a control and a data stream, found {len(members)}.")
    control = members[1]
    expected = "Q1" + base64.b64encode(hashlib.sha1(control).digest()).decode()
    if expected != index_entry["C"]:
        raise SystemExit(f"{name}: control checksum {expected} does not match the signed index ({index_entry['C']}).")
    info = untar(control).extractfile(".PKGINFO").read().decode("utf-8")
    data_hash = re.search(r"^datahash = ([0-9a-f]{64})$", info, re.M)
    if not data_hash or data_hash.group(1) != sha256(members[2]):
        raise SystemExit(f"{name}: the data stream does not match the datahash in its control file.")
    return members[2]


def package_url(filename):
    return f"{MIRROR}/{BRANCH}/{REPOSITORY}/{ARCH}/{filename}"


def lock():
    key = fetch(KEY_URL)
    if sha256(key) != KEY_SHA256:
        raise SystemExit("The Alpine signing key does not match the pinned SHA-256; refusing to continue.")
    index_bytes = fetch(f"{MIRROR}/{BRANCH}/{REPOSITORY}/{ARCH}/APKINDEX.tar.gz")
    index = verify_index(index_bytes, key)
    packages = []
    for name in PACKAGES:
        entry = index[name]
        filename = f"{name}-{entry['V']}.apk"
        apk = fetch(package_url(filename))
        verified_package(name, apk, entry)
        packages.append(
            {
                "name": name,
                "version": entry["V"],
                "file": filename,
                "url": package_url(filename),
                "sha256": sha256(apk),
                "bytes": len(apk),
                "license": entry.get("L", ""),
                "upstream": entry.get("U", ""),
                "origin": entry.get("o", name),
            }
        )
        print(f"verified {filename}  sha256 {sha256(apk)}")
    infozip = fetch(INFOZIP_SOURCE)
    licenses = {key_: {"url": url, "sha256": sha256(fetch(url))} for key_, (_, url, _) in LICENSE_TEXTS.items()}
    lock_data = {
        "alpine": {"branch": BRANCH, "repository": REPOSITORY, "arch": ARCH, "keyName": KEY_NAME, "keySha256": KEY_SHA256, "indexSha256": sha256(index_bytes)},
        "packages": packages,
        "licenses": licenses,
        "infoZipSource": {"url": INFOZIP_SOURCE, "sha256": sha256(infozip)},
    }
    LOCK.write_text(json.dumps(lock_data, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {LOCK.relative_to(ROOT)}")
    return lock_data


def add(name):
    """Verifies one more package against the live signed index and appends it to the lock; the rest stays as locked."""
    lock_data = json.loads(LOCK.read_text(encoding="utf-8"))
    if any(package["name"] == name for package in lock_data["packages"]):
        raise SystemExit(f"{name} is already in the lock.")
    if name not in PACKAGES:
        raise SystemExit(f"{name} is not in PACKAGES; add it there (and to FILES) first.")
    key = fetch(KEY_URL)
    if sha256(key) != KEY_SHA256:
        raise SystemExit("The Alpine signing key does not match the pinned SHA-256; refusing to continue.")
    index_bytes = fetch(f"{MIRROR}/{BRANCH}/{REPOSITORY}/{ARCH}/APKINDEX.tar.gz")
    entry = verify_index(index_bytes, key)[name]
    filename = f"{name}-{entry['V']}.apk"
    apk = fetch(package_url(filename))
    verified_package(name, apk, entry)
    package = {
        "name": name,
        "version": entry["V"],
        "file": filename,
        "url": package_url(filename),
        "sha256": sha256(apk),
        "bytes": len(apk),
        "license": entry.get("L", ""),
        "upstream": entry.get("U", ""),
        "origin": entry.get("o", name),
        "addedFromIndexSha256": sha256(index_bytes),
    }
    order = {n: i for i, n in enumerate(PACKAGES)}
    lock_data["packages"] = sorted(lock_data["packages"] + [package], key=lambda p: order.get(p["name"], len(order)))
    LOCK.write_text(json.dumps(lock_data, indent=2) + "\n", encoding="utf-8")
    print(f"verified {filename}  sha256 {sha256(apk)}; wrote {LOCK.relative_to(ROOT)}")
    return lock_data


def download_locked(package):
    """The package file named in the lock, from the cache or the mirror, checked against the locked SHA-256."""
    CACHE.mkdir(parents=True, exist_ok=True)
    cached = CACHE / package["file"]
    if cached.exists() and sha256(cached.read_bytes()) == package["sha256"]:
        return cached.read_bytes()
    apk = fetch(package["url"])
    if sha256(apk) != package["sha256"]:
        raise SystemExit(f"{package['file']}: SHA-256 {sha256(apk)} differs from the lock ({package['sha256']}).")
    cached.write_bytes(apk)
    return apk


def pinned_download(name, url, expected_sha256):
    """A file pinned by SHA-256 in the lock: the cached copy if it still matches, else the download (checked, then cached)."""
    CACHE.mkdir(parents=True, exist_ok=True)
    cached = CACHE / name
    if cached.exists() and sha256(cached.read_bytes()) == expected_sha256:
        return cached.read_bytes()
    data = fetch(url)
    if sha256(data) != expected_sha256:
        raise SystemExit(f"{name}: SHA-256 {sha256(data)} differs from the lock ({expected_sha256}).")
    cached.write_bytes(data)
    return data


def locked_license_text(key, entry):
    return pinned_download(f"license-{key}.txt", entry["url"], entry["sha256"]).decode("utf-8", "replace")


#  Full-screen programs run on the terminal's alternate screen, so the team gets back exactly the screen it had
#  (the Linux console's terminfo has no alternate screen of its own, which would leave the editor's text behind).
ALTERNATE_SCREEN = {"nano"}
ALTERNATE_ON = r"printf '\033[?1049h'"
ALTERNATE_OFF = r"printf '\033[?1049l'"


def wrapper(command, binary, arguments, environment):
    if binary == "busybox.static":
        return "#!/bin/sh\n" f'exec /{BASE}/elf/{binary}{arguments} "$@"\n'
    run = f'{LOADER} --library-path {LIBRARIES} /{BASE}/elf/{binary}{arguments} "$@"'
    if command in ALTERNATE_SCREEN:
        return f"#!/bin/sh\n{environment}{ALTERNATE_ON}\n{run}\nstatus=$?\n{ALTERNATE_OFF}\nexit $status\n"
    return f"#!/bin/sh\n{environment}exec {run}\n"


def build(lock_data):
    data = {}
    for package in lock_data["packages"]:
        apk = download_locked(package)
        index_entry = {"C": None}
        members = gzip_members(apk)
        info = untar(members[1]).extractfile(".PKGINFO").read().decode("utf-8")
        data_hash = re.search(r"^datahash = ([0-9a-f]{64})$", info, re.M)
        if not data_hash or data_hash.group(1) != sha256(members[2]):
            raise SystemExit(f"{package['name']}: the data stream does not match its datahash.")
        data[package["name"]] = untar(members[2])
    entries = []  # (name, kind, payload, mode)
    for name, (package, inner) in FILES.items():
        payload = data[package].extractfile(inner).read()
        executable = name.startswith(f"{BASE}/elf/") or "/lib/" in name
        entries.append((name, "file", payload, 0o755 if executable else 0o644))
    for name, target in SYMLINKS.items():
        entries.append((name, "link", target, 0o777))
    for command, (binary, arguments, environment) in COMMANDS.items():
        entries.append((f"usr/local/bin/{command}", "file", wrapper(command, binary, arguments, environment).encode(), 0o755))
    for directory in (f"{BASE}", f"{BASE}/elf", f"{BASE}/lib", f"{BASE}/share"):
        entries.append((directory, "dir", None, 0o755))
    entries.sort(key=lambda entry: entry[0])
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for name, kind, payload, mode in entries:
            info = tarfile.TarInfo(name + ("/" if kind == "dir" else ""))
            info.mtime, info.mode, info.uid, info.gid, info.uname, info.gname = EPOCH, mode, 0, 0, "", ""
            if kind == "dir":
                info.type = tarfile.DIRTYPE
                archive.addfile(info)
            elif kind == "link":
                info.type, info.linkname = tarfile.SYMTYPE, payload
                archive.addfile(info)
            else:
                info.size = len(payload)
                archive.addfile(info, io.BytesIO(payload))
    gz = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=gz, compresslevel=9, mtime=0) as stream:
        stream.write(raw.getvalue())
    ARCHIVE.write_bytes(gz.getvalue())
    unpacked = sum(len(payload) for _, kind, payload, _ in entries if kind == "file")
    print(f"wrote {ARCHIVE.relative_to(ROOT)}: {len(gz.getvalue())} bytes gzip, {unpacked} bytes unpacked, sha256 {sha256(gz.getvalue())}")
    write_licenses(lock_data)
    VERSION_FILE.write_text(
        "// Written by infrastructure/build-terminal-tools.py; do not edit.\n"
        "/** The first 12 hex digits of devfest-tools.tgz's SHA-256: the cache-busting query of its URL. */\n"
        f'export const TOOLS_VERSION = "{sha256(gz.getvalue())[:12]}";\n',
        encoding="utf-8",
    )
    return gz.getvalue()


def write_licenses(lock_data):
    infozip = pinned_download("zip30.tar.gz", lock_data["infoZipSource"]["url"], lock_data["infoZipSource"]["sha256"])
    zip_license = tarfile.open(fileobj=io.BytesIO(infozip)).extractfile("zip30/LICENSE").read().decode("utf-8", "replace")
    lines = [
        "Third-party tools of the Linux room terminal (devfest-tools.tgz)",
        "=" * 64,
        "",
        "The archive holds unmodified binaries of official Alpine Linux v3.24 x86 packages. Each package is verified against",
        "Alpine's signed package index when the lock is made (infrastructure/build-terminal-tools.py --lock) and against its",
        "SHA-256 on every build. Package sources: https://gitlab.alpinelinux.org/alpine/aports (3.24-stable branch, main/<origin>),",
        "which lists the upstream source archives; the upstream projects are named below. These tools run only inside the",
        "visitor's browser (an emulated Linux); the server does not run them.",
        "",
    ]
    for package in lock_data["packages"]:
        lines.append(f"* {package['name']} {package['version']}  license {package['license']}  upstream {package['upstream']}")
        lines.append(f"    package {package['url']}")
        lines.append(f"    package sha256 {package['sha256']}")
    lines += ["", "-" * 64, ""]
    for key, entry in lock_data["licenses"].items():
        title, _, applies = LICENSE_TEXTS[key]
        lines += [f"{title}  ({applies})", f"source: {entry['url']}", "", locked_license_text(key, entry).strip(), "", "-" * 64, ""]
    lines += ["Info-ZIP license (zip)", f"source: {lock_data['infoZipSource']['url']} (zip30/LICENSE)", "", zip_license.strip(), ""]
    LICENSE_FILE.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {LICENSE_FILE.relative_to(ROOT)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--lock", action="store_true", help="resolve and verify the newest packages of the branch and rewrite the lock file")
    parser.add_argument("--add", metavar="NAME", help="verify one more package and add it to the lock, keeping the others")
    args = parser.parse_args()
    if args.lock:
        lock_data = lock()
    elif args.add:
        lock_data = add(args.add)
    else:
        lock_data = json.loads(LOCK.read_text(encoding="utf-8"))
    build(lock_data)


if __name__ == "__main__":
    sys.exit(main())
