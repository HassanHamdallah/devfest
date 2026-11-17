#!/usr/bin/env bash
# Builds final-package.zip: the Linux-room server package for "The Last Shipment" (format: docs/LINUX-ROOM.md).
# Upload it on the final room's board under Terminal files -> Server package.
#
# The team's terminal starts in /root, so the ZIP paths are
#   root/DevFest-Crypto/participant_package/clues/clueN/clueN_key.rsa and <REPORT>.enc
#   root/DevFest-Crypto/participant_package/keys/key_A.pem .. key_D.pem
#   root/DevFest-Crypto/participant_test/            (an empty folder, kept by the package)
# and nothing else: no keys/clueN_secret.txt, no public_*.pem, no decrypted/, clues/, encrypted/ or final_test/.
# No devfest-manifest.csv: every file gets the defaults (root:root, 0644, folders 0755), which is all the challenge needs.
#
# Run:  bash docs/final/last-shipment/build-final-package.sh      (needs python3 or python; run fix-rsa-line-endings.sh first)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
py="$(command -v python3 || command -v python)"
"$py" -I - "$here" <<'PY'
import sys, zipfile
from pathlib import Path

here = Path(sys.argv[1])
source = here / "participant_package"
target = here / "final-package.zip"
prefix = "root/DevFest-Crypto"
# One fixed modification time, so the same inputs give the same ZIP bytes.
stamp = (2026, 1, 1, 0, 0, 0)

files = []
for n in range(1, 5):
    folder = source / "clues" / f"clue{n}"
    names = sorted(p.name for p in folder.iterdir())
    enc = [name for name in names if name.endswith(".enc")]
    rsa = [name for name in names if name.endswith(".rsa")]
    if len(enc) != 1 or rsa != [f"clue{n}_key.rsa"] or len(names) != 2:
        raise SystemExit(f"clue{n}: expected exactly one .enc and clue{n}_key.rsa, found {names}")
    files += [folder / rsa[0], folder / enc[0]]
for letter in "ABCD":
    files.append(source / "keys" / f"key_{letter}.pem")
if sorted(p.name for p in (source / "keys").iterdir()) != [f"key_{l}.pem" for l in "ABCD"]:
    raise SystemExit("participant_package/keys must hold exactly key_A.pem .. key_D.pem")

with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in files:
        info = zipfile.ZipInfo(f"{prefix}/participant_package/{path.relative_to(source).as_posix()}", stamp)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        archive.writestr(info, path.read_bytes())
    folder = zipfile.ZipInfo(f"{prefix}/participant_test/", stamp)
    folder.external_attr = (0o40755 << 16) | 0x10
    archive.writestr(folder, b"")

with zipfile.ZipFile(target) as archive:
    names = archive.namelist()
forbidden = ("_secret", "public_", "decrypted", "final_test", "encrypted/")
if any(bit in name for name in names for bit in forbidden):
    raise SystemExit("the package contains a file that must stay with the organizers")
print(f"wrote {target.name}: {len(names)} entries, {target.stat().st_size} bytes")
for name in names:
    print("  " + name)
PY
