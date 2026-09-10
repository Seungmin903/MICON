#!/bin/sh
set -eu

compose_file="${1:-infra/compose/mysql.compose.yml}"
service_name="mysql"

container_id="$(sudo docker compose -f "$compose_file" ps -q "$service_name")"
if [ -z "$container_id" ]; then
  echo "FAIL: MySQL container is not running" >&2
  exit 1
fi

health="$(sudo docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$container_id")"
if [ "$health" != "healthy" ]; then
  echo "FAIL: MySQL health=$health" >&2
  exit 1
fi

published_ports="$(sudo docker port "$container_id")"
if [ -n "$published_ports" ]; then
  echo "FAIL: MySQL must not publish host ports" >&2
  exit 1
fi

volume_name="$(sudo docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}{{end}}{{end}}' "$container_id")"
if [ "$volume_name" != "micon_mysql_data" ]; then
  echo "FAIL: unexpected data volume" >&2
  exit 1
fi

network_internal="$(sudo docker network inspect micon_backend --format '{{.Internal}}')"
if [ "$network_internal" != "true" ]; then
  echo "FAIL: micon_backend must be an internal network" >&2
  exit 1
fi

app_query="$(sudo docker exec --user 0 "$container_id" sh -c 'MYSQL_PWD="$(tr -d "\r\n" < /run/secrets/mysql_app_password)" mysql --protocol=tcp --host=127.0.0.1 --user=micon_app --database=micon --silent --skip-column-names --execute="SELECT 1"')"
if [ "$app_query" != "1" ]; then
  echo "FAIL: micon_app cannot query the micon database" >&2
  exit 1
fi

app_grants="$(sudo docker exec --user 0 "$container_id" sh -c 'MYSQL_PWD="$(tr -d "\r\n" < /run/secrets/mysql_app_password)" mysql --protocol=tcp --host=127.0.0.1 --user=micon_app --database=micon --silent --skip-column-names --execute="SHOW GRANTS"')"
case "$app_grants" in
  *"SELECT, INSERT, UPDATE, DELETE ON"*) ;;
  *)
    echo "FAIL: micon_app DML grants are missing" >&2
    exit 1
    ;;
esac
case "$app_grants" in
  *"ALL PRIVILEGES"*|*"CREATE"*|*"ALTER"*|*"DROP"*)
    echo "FAIL: micon_app has schema-changing privileges" >&2
    exit 1
    ;;
esac

echo "PASS: MySQL is healthy, micon_app has DML-only access, the network is internal, no host ports are published, and micon_mysql_data is mounted"
