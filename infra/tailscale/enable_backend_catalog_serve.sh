#!/bin/sh
set -eu

LOOPBACK_PORT=18080
TAILNET_PORT=18080
SERVE_TIMEOUT_SECONDS=20
CATALOG_PATH=/internal/embedding-catalog/active
TOKEN_FILE=/etc/micon/secrets/ai_service_token

if [ "$(id -u)" -ne 0 ]; then
    echo "enable_backend_catalog_serve.sh must run as root" >&2
    exit 1
fi

for command_name in tailscale python3 timeout; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "missing required command: $command_name" >&2
        exit 1
    fi
done
if [ ! -s "$TOKEN_FILE" ]; then
    echo "AI service token is missing" >&2
    exit 1
fi

temporary_dir="$(mktemp -d /tmp/micon-tailnet-catalog.XXXXXX)"
catalog_body="$temporary_dir/catalog.json"
serve_before="$temporary_dir/serve-before.json"
serve_after="$temporary_dir/serve-after.json"
serve_log="$temporary_dir/serve-enable.log"
cleanup() {
    rm -f "$catalog_body" "$serve_before" "$serve_after" "$serve_log"
    rmdir "$temporary_dir"
}
trap cleanup EXIT HUP INT TERM

timeout 10s tailscale status --json | python3 -c '
import json, sys
state = json.load(sys.stdin)
if state.get("BackendState") != "Running" or not (state.get("Self") or {}).get("Online"):
    raise SystemExit("Tailscale is not online")
'

python3 - "$TOKEN_FILE" "$catalog_body" "http://127.0.0.1:$LOOPBACK_PORT$CATALOG_PATH" <<'PY'
import json
import sys
import urllib.error
import urllib.request

with open(sys.argv[1], encoding="utf-8") as handle:
    token = handle.read().strip()
request = urllib.request.Request(
    sys.argv[3],
    headers={"Accept": "application/json", "Authorization": f"Bearer {token}"},
)
try:
    with urllib.request.urlopen(request, timeout=15) as response:
        if response.status != 200:
            raise SystemExit(f"loopback catalog bridge returned HTTP {response.status}")
        body = json.load(response)
except urllib.error.HTTPError as error:
    raise SystemExit(f"loopback catalog bridge returned HTTP {error.code}") from None
except (urllib.error.URLError, TimeoutError, OSError, ValueError):
    raise SystemExit("loopback catalog bridge request failed") from None
with open(sys.argv[2], "w", encoding="utf-8") as handle:
    json.dump(body, handle)
with open(sys.argv[2], encoding="utf-8") as handle:
    body = json.load(handle)
data = body.get("data") or body
tracks = data.get("tracks") or []
if not tracks:
    raise SystemExit("active embedding catalog has no tracks")
for track in tracks:
    if track.get("catalog_track_id") is None or track.get("song_id") is None:
        raise SystemExit("active embedding catalog contains an incomplete mapping")
PY

timeout 10s tailscale serve status --json > "$serve_before"
if python3 - "$serve_before" "$LOOPBACK_PORT" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    config = json.load(handle)
needle = f":{sys.argv[2]}"
serialized = json.dumps(config, sort_keys=True)
if config and needle not in serialized:
    raise SystemExit(42)
PY
then
    :
else
    result=$?
    if [ "$result" -eq 42 ]; then
        echo "refusing to replace an unrelated Tailscale Serve configuration" >&2
    fi
    exit "$result"
fi

set +e
timeout --kill-after=5s "${SERVE_TIMEOUT_SECONDS}s" \
    tailscale serve --bg --yes --http="$TAILNET_PORT" "$LOOPBACK_PORT" \
    > "$serve_log" 2>&1
serve_result=$?
set -e
timeout 10s tailscale serve status --json > "$serve_after"
python3 - "$serve_after" "$LOOPBACK_PORT" "$TAILNET_PORT" "$serve_result" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    config = json.load(handle)
serialized = json.dumps(config, sort_keys=True)
target = f"127.0.0.1:{sys.argv[2]}"
listener = f":{sys.argv[3]}"
if not config or target not in serialized or listener not in serialized:
    raise SystemExit(
        f"Tailscale Serve did not retain the catalog bridge target (client result {sys.argv[4]})"
    )
PY

echo "tailscale_catalog_serve result=active mode=tailnet_http port=$TAILNET_PORT target=127.0.0.1:$LOOPBACK_PORT"
