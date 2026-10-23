#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

# Export the whole table a22.anomalie into a gzipped CSV, verify the row count, and
# optionally drop the table.
#
# Usage: PGHOST=... PGUSER=... PGDATABASE=... ./a22db_archive_anomalie.sh [--drop] [out_dir]
# Optional env:
#   S3_URI   if set (e.g. s3://bucket/a22-archive), upload the output directory with the aws cli
#
# Without --drop the database is only read. With --drop the table is dropped only if
# the exported row count matches the table, the S3 upload (if any) succeeded, and
# you type the table name at the prompt.

set -euo pipefail

DROP=0
if [ "${1:-}" = "--drop" ]; then DROP=1; shift; fi
OUT="${1:-./a22_anomalie_archive}"
FILE="$OUT/a22_anomalie.csv.gz"

if command -v pigz >/dev/null; then GZ="pigz -6"; else GZ="gzip -6"; fi

mkdir -p "$OUT"

psql -Atq -c "select column_name || ' ' || data_type from information_schema.columns
              where table_schema = 'a22' and table_name = 'anomalie' order by ordinal_position" \
    > "$OUT/schema.txt"

before=$(psql -Atq -c 'select count(*) from a22.anomalie')
echo "rows in table: $before"

if [ -f "$FILE" ]; then
    echo "reusing existing $FILE"
else
    psql -q -c '\copy a22.anomalie to stdout with (format csv, header, null '"''"')' | $GZ > "$FILE.part"
    mv "$FILE.part" "$FILE"
fi

exported=$(( $(zcat "$FILE" | wc -l) - 1 ))
echo "rows exported: $exported"

# csv quoting keeps embedded newlines out of this schema, so line count equals row count
after=$(psql -Atq -c 'select count(*) from a22.anomalie')
if [ "$before" != "$exported" ] || [ "$before" != "$after" ]; then
    echo "row count mismatch (before=$before exported=$exported after=$after), aborting" >&2
    exit 1
fi

(cd "$OUT" && sha256sum a22_anomalie.csv.gz > SHA256SUMS)
echo "$before" > "$OUT/row_count.txt"

if [ -n "${S3_URI:-}" ]; then
    aws s3 sync "$OUT" "$S3_URI"
fi

if [ "$DROP" -eq 1 ]; then
    read -r -p "Type the table name 'a22.anomalie' to DROP it on $(psql -Atq -c 'select current_database()') @ ${PGHOST:-local}: " answer
    [ "$answer" = "a22.anomalie" ] || { echo "not confirmed, nothing dropped"; exit 1; }
    psql -v ON_ERROR_STOP=1 -c 'DROP TABLE a22.anomalie'
    echo "dropped a22.anomalie"
else
    echo "export verified, table kept (use --drop to drop it)"
fi
