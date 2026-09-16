#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
    echo "rollback_backend_release.sh must run as root" >&2
    exit 1
fi
if [ "${1:-}" != "--confirm-db-compatible" ] || [ "$#" -ne 1 ]; then
    echo "usage: rollback_backend_release.sh --confirm-db-compatible" >&2
    exit 2
fi

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPOSITORY_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
STATE_DIR=/var/lib/micon
STATE_FILE="$STATE_DIR/backend-release.env"
PREVIOUS_STATE_FILE="$STATE_DIR/backend-release.previous.env"
DEPLOY_ENV=/etc/micon/runtime/backend-deploy.env
LOCK_FILE=/run/lock/micon-backend-deploy.lock

if ! command -v flock >/dev/null 2>&1; then
    echo "flock is required for serialized backend rollback" >&2
    exit 1
fi
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
    echo "another backend deploy or rollback is already running" >&2
    exit 1
fi

validate_root_state() {
    state_path="$1"
    if [ ! -f "$state_path" ] || [ -L "$state_path" ] || [ "$(stat -c %u "$state_path")" != 0 ]; then
        echo "backend rollback requires a root-owned regular state file" >&2
        exit 1
    fi
    case "$(stat -c %a "$state_path")" in
        600|640) ;;
        *) echo "backend rollback state must have mode 0600 or 0640" >&2; exit 1 ;;
    esac
}

state_value() {
    state_path="$1"
    state_key="$2"
    value="$(sed -n "s/^${state_key}=//p" "$state_path")"
    if [ -z "$value" ] || [ "$(grep -c "^${state_key}=" "$state_path")" -ne 1 ]; then
        echo "backend rollback state is missing a unique $state_key" >&2
        exit 1
    fi
    printf '%s\n' "$value"
}

validate_root_state "$STATE_FILE"
validate_root_state "$PREVIOUS_STATE_FILE"

active_sha="$(state_value "$STATE_FILE" RELEASE_SHA)"
active_slot="$(state_value "$STATE_FILE" SLOT)"
target_sha="$(state_value "$PREVIOUS_STATE_FILE" RELEASE_SHA)"
target_slot="$(state_value "$PREVIOUS_STATE_FILE" SLOT)"
target_port="$(state_value "$PREVIOUS_STATE_FILE" PORT)"
target_image_id="$(state_value "$PREVIOUS_STATE_FILE" IMAGE_ID)"

for release_sha in "$active_sha" "$target_sha"; do
    case "$release_sha" in
        *[!0-9a-f]*|'') echo "backend rollback state has an invalid release SHA" >&2; exit 1 ;;
    esac
    if [ "${#release_sha}" -ne 40 ]; then
        echo "backend rollback state has an invalid release SHA" >&2
        exit 1
    fi
done
case "$active_slot:$target_slot:$target_port" in
    blue:green:18082|green:blue:18081) ;;
    *) echo "backend rollback state does not describe opposite approved slots" >&2; exit 1 ;;
esac

active_link=/etc/nginx/micon/backend-active.conf
expected_active_target="/etc/nginx/micon/backend-slots/$active_slot.conf"
if [ ! -L "$active_link" ] || [ "$(readlink "$active_link")" != "$expected_active_target" ]; then
    echo "backend rollback state does not match the active Nginx slot" >&2
    exit 1
fi

target_container="micon-backend-$target_slot"
if [ "$(docker inspect --format '{{.State.Running}}' "$target_container" 2>/dev/null || true)" != true ]; then
    echo "previous backend slot is not running" >&2
    exit 1
fi
actual_target_image_id="$(docker inspect --format '{{.Image}}' "$target_container")"
if [ "$actual_target_image_id" != "$target_image_id" ]; then
    echo "previous backend slot image does not match recorded state" >&2
    exit 1
fi

if [ ! -f "$DEPLOY_ENV" ] || [ -L "$DEPLOY_ENV" ] || [ "$(stat -c %u "$DEPLOY_ENV")" != 0 ]; then
    echo "backend deployment settings must be a root-owned regular file" >&2
    exit 1
fi
case "$(stat -c %a "$DEPLOY_ENV")" in
    400|600) ;;
    *) echo "backend deployment settings must have mode 0400 or 0600" >&2; exit 1 ;;
esac
set -a
. "$DEPLOY_ENV"
set +a
: "${MICON_CATALOG_SMOKE_SONG_ID:?Set catalog smoke song ID}"
: "${MICON_CATALOG_BUCKET:?Set catalog bucket}"
: "${MICON_CATALOG_REGION:?Set catalog region}"
: "${MICON_CATALOG_ALLOWED_PREFIX:?Set catalog prefix}"

python3 "$REPOSITORY_ROOT/infra/deploy/check_health.py" \
    --url "http://127.0.0.1:$target_port/actuator/health/readiness" \
    --deadline 60 --interval 2 --timeout 5 --required-successes 3
python3 "$REPOSITORY_ROOT/infra/deploy/check_catalog_media.py" \
    --base-url "http://127.0.0.1:$target_port" \
    --song-id "$MICON_CATALOG_SMOKE_SONG_ID" \
    --bucket "$MICON_CATALOG_BUCKET" \
    --region "$MICON_CATALOG_REGION" \
    --allowed-prefix "$MICON_CATALOG_ALLOWED_PREFIX" \
    --timeout 30

current_copy="$(mktemp "$STATE_DIR/backend-release.rollback-current.XXXXXX")"
target_copy="$(mktemp "$STATE_DIR/backend-release.rollback-target.XXXXXX")"
state_updated=false
cleanup_state_copies() {
    if [ "$state_updated" = false ]; then
        rm -f "$current_copy" "$target_copy"
    fi
}
trap cleanup_state_copies EXIT
trap 'exit 1' HUP INT TERM
install -o root -g root -m 0640 "$STATE_FILE" "$current_copy"
sed '/^ROLLED_BACK_AT_UTC=/d' "$PREVIOUS_STATE_FILE" > "$target_copy"
printf 'ROLLED_BACK_AT_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$target_copy"
chown root:root "$target_copy"
chmod 0640 "$target_copy"

"$REPOSITORY_ROOT/infra/nginx/switch_backend_slot.sh" "$target_slot"
mv -Tf "$target_copy" "$STATE_FILE"
mv -Tf "$current_copy" "$PREVIOUS_STATE_FILE"
state_updated=true
trap - EXIT HUP INT TERM

printf 'backend_rollback result=active sha=%s slot=%s previous_sha=%s\n' \
    "$target_sha" "$target_slot" "$active_sha"
