#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

# Export the a22.a22_traffic rows of one calendar year (UTC) into a gzipped CSV,
# plus a schema description, the db row count and a checksum.
# CSV is independent of the Postgres version.
#
# Usage: PGHOST=... PGUSER=... PGDATABASE=... ./a22db_archive_traffic.sh <year> [out_dir]
# Optional env:
#   S3_URI   if set (e.g. s3://bucket/a22-archive), upload the output directory with the aws cli
#
# This script only reads; it does NOT delete anything from the database.

set -euo pipefail

YEAR="${1:?usage: $0 <year> [out_dir]}"
[[ "$YEAR" =~ ^[0-9]{4}$ ]] || { echo "invalid year: $YEAR" >&2; exit 1; }
OUT="${2:-./a22_traffic_archive}"
FILE="$OUT/a22_traffic_${YEAR}.csv.gz"
COLS='stationcode, "timestamp", distance, headway, length, axles, against_traffic, class, speed, direction, country, license_plate_initials'

if command -v pigz >/dev/null; then GZ="pigz -6"; else GZ="gzip -6"; fi

mkdir -p "$OUT"

from=$(date -u -d "$YEAR-01-01" +%s)
to=$(date -u -d "$((YEAR + 1))-01-01" +%s)

psql -Atq -c "select column_name || ' ' || data_type from information_schema.columns
              where table_schema = 'a22' and table_name = 'a22_traffic' order by ordinal_position" \
    > "$OUT/schema_${YEAR}.txt"

if [ -f "$FILE" ]; then
    echo "reusing existing $FILE"
else
    echo "export $FILE"
    psql -q -c "\\copy (select $COLS from a22.a22_traffic where \"timestamp\" >= $from and \"timestamp\" < $to) to stdout with (format csv, header, null '')" \
        | $GZ > "$FILE.part"
    mv "$FILE.part" "$FILE"
fi

db_rows=$(psql -Atq -c "select count(*) from a22.a22_traffic where \"timestamp\" >= $from and \"timestamp\" < $to")
file_rows=$(( $(zcat "$FILE" | wc -l) - 1 ))
echo "rows in db: $db_rows, rows in file: $file_rows"
if [ "$db_rows" != "$file_rows" ]; then
    echo "row count mismatch, not writing checksum" >&2
    exit 1
fi
echo "$db_rows" > "$OUT/row_count_${YEAR}.txt"

(cd "$OUT" && sha256sum "a22_traffic_${YEAR}.csv.gz" > "SHA256SUMS_${YEAR}")

if [ -n "${S3_URI:-}" ]; then
    aws s3 sync "$OUT" "$S3_URI"
fi

echo "done: $FILE"
