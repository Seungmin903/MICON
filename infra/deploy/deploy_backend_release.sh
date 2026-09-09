#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
    echo "deploy_backend_release.sh must run as root" >&2
    exit 1
fi

release_sha="${1:-}"
case "$release_sha" in
    *[!0-9a-f]*|'') echo "release SHA must be 40 lowercase hexadecimal characters" >&2; exit 2 ;;
esac
if [ "${#release_sha}" -ne 40 ]; then
    echo "release SHA must be 40 lowercase hexadecimal characters" >&2
    exit 2
fi

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPOSITORY_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
DEPLOY_ENV=/etc/micon/runtime/backend-deploy.env
COMPOSE_FILE="$REPOSITORY_ROOT/infra/compose/backend.compose.yml"
STATE_DIR=/var/lib/micon
STATE_FILE="$STATE_DIR/backend-release.env"
PREVIOUS_STATE_FILE="$STATE_DIR/backend-release.previous.env"

if [ -e "$STATE_FILE" ]; then
    if [ ! -f "$STATE_FILE" ] || [ -L "$STATE_FILE" ] || [ "$(stat -c %u "$STATE_FILE")" != 0 ]; then
        echo "backend release state must be a root-owned regular file" >&2
        exit 1
    fi
    case "$(stat -c %a "$STATE_FILE")" in
        600|640) ;;
        *) echo "backend release state must have mode 0600 or 0640" >&2; exit 1 ;;
    esac
fi

"$REPOSITORY_ROOT/infra/deploy/prepare_backend_host.sh"

if [ ! -f "$DEPLOY_ENV" ] || [ "$(stat -c %u "$DEPLOY_ENV")" != 0 ]; then
    echo "backend deployment settings must be a root-owned regular file" >&2
    exit 1
fi
case "$(stat -c %a "$DEPLOY_ENV")" in
    400|600) ;;
    *) echo "backend deployment settings must have mode 0400 or 0600" >&2; exit 1 ;;
esac

# The file is root-owned and contains non-secret gate identifiers only.
set -a
. "$DEPLOY_ENV"
set +a
: "${MICON_CATALOG_SMOKE_SONG_ID:?Set catalog smoke song ID}"
: "${MICON_CATALOG_BUCKET:?Set catalog bucket}"
: "${MICON_CATALOG_REGION:?Set catalog region}"
: "${MICON_CATALOG_ALLOWED_PREFIX:?Set catalog prefix}"
case "$MICON_CATALOG_SMOKE_SONG_ID" in
    *[!0-9]*|'') echo "catalog smoke song ID must be a positive integer" >&2; exit 2 ;;
esac
if [ "$MICON_CATALOG_SMOKE_SONG_ID" -lt 1 ]; then
    echo "catalog smoke song ID must be a positive integer" >&2
    exit 2
fi

active_slot=
if [ -L /etc/nginx/micon/backend-active.conf ]; then
    active_target="$(readlink /etc/nginx/micon/backend-active.conf)"
    case "$active_target" in
        /etc/nginx/micon/backend-slots/blue.conf) active_slot=blue ;;
        /etc/nginx/micon/backend-slots/green.conf) active_slot=green ;;
        *) echo "refusing unknown active backend target" >&2; exit 1 ;;
    esac
fi

case "$active_slot" in
    blue) candidate_slot=green; candidate_port=18082 ;;
    green|'') candidate_slot=blue; candidate_port=18081 ;;
esac

export MICON_BACKEND_IMAGE="micon-backend:$release_sha"
export MICON_BACKEND_SLOT="$candidate_slot"
export MICON_BACKEND_BIND_PORT="$candidate_port"
export MICON_BACKEND_ENV_FILE=/etc/micon/runtime/backend.env

candidate_started=false
route_switched=false
cleanup_failed_candidate() {
    result=$?
    trap - EXIT HUP INT TERM
    if [ "$result" -ne 0 ] && [ "$candidate_started" = true ] && [ "$route_switched" = false ]; then
        docker compose -f "$COMPOSE_FILE" down || true
        echo "backend_release cleanup=candidate_removed route=unchanged" >&2
    fi
    exit "$result"
}
trap cleanup_failed_candidate EXIT
trap 'exit 1' HUP INT TERM

docker compose -f "$COMPOSE_FILE" config --quiet
docker compose --progress=plain -f "$COMPOSE_FILE" build backend
candidate_started=true
docker compose -f "$COMPOSE_FILE" up -d --no-build backend

python3 "$REPOSITORY_ROOT/infra/deploy/check_health.py" \
    --url "http://127.0.0.1:$candidate_port/actuator/health/readiness" \
    --deadline 60 --interval 2 --timeout 5 --required-successes 3
python3 "$REPOSITORY_ROOT/infra/deploy/check_catalog_media.py" \
    --base-url "http://127.0.0.1:$candidate_port" \
    --song-id "$MICON_CATALOG_SMOKE_SONG_ID" \
    --token-file /etc/micon/secrets/ai_service_token \
    --bucket "$MICON_CATALOG_BUCKET" \
    --region "$MICON_CATALOG_REGION" \
    --allowed-prefix "$MICON_CATALOG_ALLOWED_PREFIX" \
    --timeout 30

"$REPOSITORY_ROOT/infra/nginx/switch_backend_slot.sh" "$candidate_slot"
route_switched=true

image_id="$(docker image inspect --format '{{.Id}}' "$MICON_BACKEND_IMAGE")"
install -d -o root -g root -m 0750 "$STATE_DIR"
if [ -f "$STATE_FILE" ]; then
    previous_temp="$(mktemp "$STATE_DIR/backend-release.previous.XXXXXX")"
    install -o root -g root -m 0640 "$STATE_FILE" "$previous_temp"
    mv -Tf "$previous_temp" "$PREVIOUS_STATE_FILE"
fi
state_temp="$(mktemp "$STATE_DIR/backend-release.XXXXXX")"
chown root:root "$state_temp"
chmod 0640 "$state_temp"
{
    printf 'RELEASE_SHA=%s\n' "$release_sha"
    printf 'IMAGE=%s\n' "$MICON_BACKEND_IMAGE"
    printf 'IMAGE_ID=%s\n' "$image_id"
    printf 'SLOT=%s\n' "$candidate_slot"
    printf 'PORT=%s\n' "$candidate_port"
    printf 'DEPLOYED_AT_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$state_temp"
mv -Tf "$state_temp" "$STATE_FILE"

trap - EXIT HUP INT TERM
printf 'backend_release result=active sha=%s slot=%s port=%s image_id=%s\n' \
    "$release_sha" "$candidate_slot" "$candidate_port" "$image_id"
