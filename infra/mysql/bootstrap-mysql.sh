#!/bin/sh
set -eu

compose_file="${1:-infra/compose/mysql.compose.yml}"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
init_script="$script_dir/init/10-create-app-user.sh"

container_id="$(sudo docker compose -f "$compose_file" ps -q mysql)"
if [ -z "$container_id" ]; then
  echo "FAIL: MySQL container is not running" >&2
  exit 1
fi

# Run as container root so host secret files can remain root:root 0600.
# The SQL is idempotent, so rerunning this after a container recreation is safe.
sudo docker exec --user 0 -i "$container_id" sh -s < "$init_script"
echo "PASS: MySQL application account is ready"
