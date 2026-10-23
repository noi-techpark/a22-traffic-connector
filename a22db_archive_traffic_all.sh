#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

# Run a22db_archive_traffic.sh for each year in a range, one after another.
# Years that already have a SHA256SUMS_<year> file in out_dir are skipped.
#
# Usage: PGHOST=... PGUSER=... PGDATABASE=... ./a22db_archive_traffic_all.sh [out_dir] [first_year] [last_year]

set -euo pipefail

OUT="${1:-./a22_traffic_archive}"
FIRST="${2:-2017}"
LAST="${3:-2024}"
DIR="$(dirname "$0")"

for year in $(seq "$FIRST" "$LAST"); do
    if [ -f "$OUT/SHA256SUMS_$year" ]; then
        echo "skip $year (already done)"
        continue
    fi
    "$DIR/a22db_archive_traffic.sh" "$year" "$OUT"
done
