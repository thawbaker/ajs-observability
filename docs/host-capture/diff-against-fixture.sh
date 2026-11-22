#!/usr/bin/env bash
# Diff the real host's column sets against the authored fixture.
#
# This is the deliverable that retires risk R1. It reports, per file:
#   - columns the APPLICATION reads but the host does not have  (will fail at runtime)
#   - columns the host has that the application ignores          (harmless, but new)
#
# Usage: docs/host-capture/diff-against-fixture.sh out/01-columns.txt
#
# The input is the raw QSYS2.SYSCOLUMNS output from 01-columns.sql. It is parsed
# loosely on purpose: the exact column order and spacing differ between STRSQL,
# ACS and a jt400 dump, so anything that looks like TABLE_NAME / COLUMN_NAME is
# picked up rather than relying on fixed positions.

set -euo pipefail

CAPTURE="${1:-}"
if [[ -z "$CAPTURE" || ! -f "$CAPTURE" ]]; then
  echo "usage: $0 <out/01-columns.txt>" >&2
  exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$REPO_ROOT/src/main/resources/fixture/schema-ajs.sql"

if [[ ! -f "$FIXTURE" ]]; then
  echo "fixture not found: $FIXTURE" >&2
  exit 2
fi

# Columns the fixture declares, per table. Parsed from the CREATE TABLE blocks.
fixture_columns() {
  awk -v want="$1" '
    $0 ~ "CREATE TABLE IF NOT EXISTS QUSRIJS\\." want " *\\(" { inside = 1; next }
    inside && /^\);/ { inside = 0 }
    inside {
      # strip comments and trailing comma, take the first token
      sub(/--.*/, "")
      gsub(/^[ \t]+|[ \t]+$/, "")
      if ($0 == "") next
      sub(/,$/, "")
      name = $1
      gsub(/[(),]/, "", name)
      if (name != "" && name != "PRIMARY" && name != "CONSTRAINT") print toupper(name)
    }
  ' "$FIXTURE"
}

# Columns the host reports for a table, from the capture file.
host_columns() {
  awk -v want="$1" '
    toupper($0) ~ toupper(want) {
      # Take the last all-caps token that looks like a column name on that line.
      # Lines from SYSCOLUMNS put TABLE_NAME first and COLUMN_NAME third, but
      # clients vary; prefer an explicit "COLUMN_NAME" header match if present.
      n = split($0, f, /[ \t|]+/)
      for (i = 1; i <= n; i++) {
        tok = toupper(f[i])
        if (tok ~ /^(JM|HS|CM)[A-Z0-9]+$/) print tok
      }
    }
  ' "$CAPTURE" | sort -u
}

status=0

for table in QAIJSMST QAIJSHST QAIJS1CM; do
  echo "=== $table ==="
  fcols="$(fixture_columns "$table" | sort -u)"
  hcols="$(host_columns "$table")"

  if [[ -z "$hcols" ]]; then
    echo "  no host rows found in the capture — did 01-columns.sql run for this file?"
    echo "  (the capture may be empty or formatted differently; check it by hand)"
    status=1
    echo
    continue
  fi

  missing="$(comm -23 <(echo "$fcols") <(echo "$hcols"))"
  extra="$(comm -13 <(echo "$fcols") <(echo "$hcols"))"

  if [[ -n "$missing" ]]; then
    echo "  FIXTURE HAS, HOST DOES NOT (these will fail at runtime):"
    echo "$missing" | sed 's/^/    - /'
    status=1
  else
    echo "  every fixture column exists on the host"
  fi

  if [[ -n "$extra" ]]; then
    echo "  HOST HAS, FIXTURE DOES NOT (harmless, but the fixture is incomplete):"
    echo "$extra" | sed 's/^/    - /'
  fi
  echo
done

echo "----------------------------------------"
if [[ $status -eq 0 ]]; then
  echo "RESULT: the authored fixture's columns are a subset of the host's."
  echo "R1 is retired for the column set. Still open: the QAIJSHST key (02-keys.sql)."
else
  echo "RESULT: the fixture and the host disagree. Update the fixture from the"
  echo "capture, keep the old version as schema-ajs-authored.sql, and re-run"
  echo "./gradlew test. Do not edit the capture."
fi
exit $status
