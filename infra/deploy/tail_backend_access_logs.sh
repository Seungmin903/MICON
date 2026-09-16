#!/bin/sh
# Read-only: follow the active slot, including a blue/green switch during QA.
set -eu
active=/etc/nginx/micon/backend-active.conf
mode="${1:-follow}"
case "$mode" in follow|recent) ;; *) echo "usage: $0 [follow|recent]" >&2; exit 2 ;; esac

slot_container() {
    case "$(readlink "$active")" in
        /etc/nginx/micon/backend-slots/blue.conf) printf '%s' micon-backend-blue ;;
        /etc/nginx/micon/backend-slots/green.conf) printf '%s' micon-backend-green ;;
        *) echo "unknown active backend slot" >&2; exit 1 ;;
    esac
}

container="$(slot_container)"
if [ "$mode" = recent ]; then
    docker logs --since 15m --tail 3000 "$container" 2>&1 | grep 'event=' || true
    exit 0
fi

# GNU timeout bounds each follow so the active slot is re-read every 10 seconds.
# First pass includes two minutes, then replay from the start of each previous
# window. This also covers a new slot's requests before a switch is detected.
since=2m
previous=
trap 'exit 0' INT TERM
while :; do
    container="$(slot_container)"
    if [ "$container" != "$previous" ]; then
        printf 'Following %s (Ctrl+C to stop)\n' "$container" >&2
        previous="$container"
    fi
    next_since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    timeout --signal=TERM 10s docker logs --follow --since "$since" "$container" 2>&1 |
        grep --line-buffered 'event=' || true
    since="$next_since"
done
