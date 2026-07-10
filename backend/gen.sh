#!/usr/bin/env bash
# generate PigeonSMS invite codes.
#   ./gen.sh <count> <uses> [expires_hours]
#   ./gen.sh 2 2        -> 2 codes, 2 uses each, never expire
# Needs PIGEON_ADMIN_TOKEN in env or in backend/.env
set -euo pipefail

COUNT="${1:-1}"
USES="${2:-1}"
EXPIRES="${3:-}"
API="${PIGEON_API:-https://api.pigeonsms.aldi.best}"
DIR="$(cd "$(dirname "$0")" && pwd)"

if [[ -z "${PIGEON_ADMIN_TOKEN:-}" && -f "$DIR/.env" ]]; then
  # shellcheck disable=SC1091
  source "$DIR/.env"
fi
if [[ -z "${PIGEON_ADMIN_TOKEN:-}" ]]; then
  echo "error: PIGEON_ADMIN_TOKEN not set (export it or put it in backend/.env)" >&2
  exit 1
fi

BODY="{\"count\": $COUNT, \"uses\": $USES"
[[ -n "$EXPIRES" ]] && BODY+=", \"expires_hours\": $EXPIRES"
BODY+="}"

RESP=$(curl -sS -X POST "$API/admin/invites" \
  -H "Authorization: Bearer $PIGEON_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$BODY")

echo "$RESP" | node -e '
let s = "";
process.stdin.on("data", (d) => (s += d)).on("end", () => {
  let r;
  try { r = JSON.parse(s); } catch { console.error("bad response:", s); process.exit(1); }
  if (r.error) { console.error("error:", r.error.message); process.exit(1); }
  for (const i of r.invites) {
    const exp = i.expires_at ? ", expires " + new Date(i.expires_at).toISOString() : "";
    console.log(`${i.code}  (${i.max_uses} uses${exp})`);
  }
});'
