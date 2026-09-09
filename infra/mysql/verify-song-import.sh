#!/usr/bin/env sh
set -eu

MYSQL_HOST="${MYSQL_HOST:-mysql}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-ci-root-password}"
MYSQL_DATABASE="${MYSQL_DATABASE:-micon}"

export MYSQL_PWD="$MYSQL_PASSWORD"

until mysqladmin --protocol=tcp --host="$MYSQL_HOST" --port="$MYSQL_PORT" --user="$MYSQL_USER" ping >/dev/null 2>&1; do
    sleep 2
done

mysql --protocol=tcp --host="$MYSQL_HOST" --port="$MYSQL_PORT" --user="$MYSQL_USER" \
    < docs/MICON_COMPLETE_DDL.sql
mysql --protocol=tcp --host="$MYSQL_HOST" --port="$MYSQL_PORT" --user="$MYSQL_USER" \
    < docs/genre_seed.sql

# The generated import is intentionally run twice to verify its upsert contract.
mysql --protocol=tcp --host="$MYSQL_HOST" --port="$MYSQL_PORT" --user="$MYSQL_USER" \
    < docs/section_metadata_insert.sql
mysql --protocol=tcp --host="$MYSQL_HOST" --port="$MYSQL_PORT" --user="$MYSQL_USER" \
    < docs/section_metadata_insert.sql

mysql --protocol=tcp --host="$MYSQL_HOST" --port="$MYSQL_PORT" --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names \
    -e "SELECT CONCAT('songs=', COUNT(*)) FROM song; SELECT CONCAT('sections=', COUNT(*)) FROM song_section; SELECT CONCAT('song_metadata=', COUNT(*)) FROM song_metadata;"
