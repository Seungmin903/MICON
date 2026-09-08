#!/bin/sh
set -eu

# Invoked by bootstrap-mysql.sh after MySQL becomes healthy. It is idempotent;
# secret values are read from mounted files and are never printed.
app_user="${MYSQL_APP_USER:-micon_app}"
app_password_file="${MYSQL_APP_PASSWORD_FILE:-/run/secrets/mysql_app_password}"
root_password_file="${MYSQL_ROOT_PASSWORD_FILE:-/run/secrets/mysql_root_password}"

case "$app_user" in
  *[!a-zA-Z0-9_]*)
    echo "MYSQL_APP_USER contains an unsupported character" >&2
    exit 1
    ;;
esac

app_password="$(tr -d '\r\n' < "$app_password_file")"
root_password="$(tr -d '\r\n' < "$root_password_file")"

if [ -z "$app_password" ] || [ -z "$root_password" ]; then
  echo "MySQL secret files must not be empty" >&2
  exit 1
fi

client_config="$(mktemp)"
trap 'rm -f "$client_config"' EXIT HUP INT TERM
chmod 600 "$client_config"
{
  printf '%s\n' '[client]'
  printf '%s\n' 'user=root'
  printf 'password=%s\n' "$root_password"
  printf '%s\n' 'protocol=socket'
} > "$client_config"

mysql --defaults-extra-file="$client_config" <<SQL
CREATE USER IF NOT EXISTS '${app_user}'@'%' IDENTIFIED BY '${app_password}';
ALTER USER '${app_user}'@'%' IDENTIFIED BY '${app_password}';
GRANT SELECT, INSERT, UPDATE, DELETE ON micon.* TO '${app_user}'@'%';
FLUSH PRIVILEGES;
SQL

unset app_password root_password
