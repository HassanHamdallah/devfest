#!/usr/bin/env bash
# Re-creates the four clueN_key.rsa files so they decrypt to "<64 hex characters>\n" (a single LF).
#
# Why: keys/clueN_secret.txt is 64 hex characters followed by CRLF. The old .rsa files held that CRLF too, and
# "openssl enc -pass file:secret_N.txt" on Linux then keeps the \r as part of the passphrase and fails with "bad decrypt".
# The .enc files were made with the 64 hex characters as the passphrase; a trailing LF is stripped by openssl
# (it reads the first line of a -pass file), a CR is not. This script never touches the .enc files or the secrets.
#
# Run it from anywhere:  bash docs/final/last-shipment/fix-rsa-line-endings.sh
# Repeatable: RSA-OAEP is randomized, so every run writes different .rsa bytes that all decrypt to the same secret.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here"

# clue number : key letter : report name   (key shuffle: clue1 -> C, clue2 -> A, clue3 -> D, clue4 -> B)
clues=("1:C:ORION_SHIPMENT_NOTICE" "2:A:SECURITY_ACCESS_REPORT" "3:D:WAREHOUSE_MOVEMENT_REPORT" "4:B:STAFF_ACTIVITY_AUDIT")
# A folder next to the script (not /tmp): a Windows build of openssl cannot open Git Bash's /tmp.
work=".fix-rsa-work"
rm -rf "$work"; mkdir "$work"
trap 'rm -rf "$work"' EXIT

for entry in "${clues[@]}"; do
  IFS=: read -r n x report <<<"$entry"
  secret="$(tr -d '\r\n' <"keys/clue${n}_secret.txt")"
  if ! [[ "$secret" =~ ^[0-9a-f]{64}$ ]]; then
    echo "clue${n}: keys/clue${n}_secret.txt is not 64 lower-case hex characters" >&2
    exit 1
  fi
  # The secret followed by exactly one LF, no CR.
  printf '%s\n' "$secret" >"$work/secret_${n}.txt"

  # The secret must open the unchanged .enc file before anything is written.
  openssl enc -d -aes-256-cbc -pbkdf2 -in "encrypted/${report}.enc" -out "$work/report_${n}.txt" -pass "file:$work/secret_${n}.txt"
  # Compared without CR: a Windows checkout may turn the plain-text copy in clues/ into CRLF; the .enc holds LF.
  cmp -s <(tr -d '\r' <"$work/report_${n}.txt") <(tr -d '\r' <"clues/${report}.txt") || { echo "clue${n}: decrypted report differs from clues/${report}.txt" >&2; exit 1; }

  openssl pkeyutl -encrypt -pubin -inkey "keys/public_${x}.pem" -in "$work/secret_${n}.txt" -out "$work/clue${n}_key.rsa" \
    -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256

  # Round trip with the private key the participants get.
  openssl pkeyutl -decrypt -inkey "participant_package/keys/key_${x}.pem" -in "$work/clue${n}_key.rsa" -out "$work/back_${n}.txt" \
    -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256
  cmp -s "$work/back_${n}.txt" "$work/secret_${n}.txt" || { echo "clue${n}: the new .rsa does not decrypt to the secret" >&2; exit 1; }

  cp "$work/clue${n}_key.rsa" "participant_package/clues/clue${n}/clue${n}_key.rsa"
  cp "$work/clue${n}_key.rsa" "encrypted/clue${n}_key.rsa"
  echo "clue${n}: key ${x} -> clue${n}_key.rsa rewritten (65-byte secret, LF only), ${report}.enc untouched"
done
