#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPOSITORY_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
NGINX_SITE=/etc/nginx/sites-available/micon-https.conf
NGINX_SLOT_DIR=/etc/nginx/micon/backend-slots
NGINX_ACTIVE=/etc/nginx/micon/backend-active.conf
BACKUP_ROOT=/var/backups/micon
DOMAIN=j15b110.p.ssafy.io

if [ "$(id -u)" -ne 0 ]; then
    echo "switch_backend_slot.sh must run as root" >&2
    exit 1
fi

slot="${1:-}"
case "$slot" in
    blue) port=18081 ;;
    green) port=18082 ;;
    *) echo "usage: switch_backend_slot.sh blue|green" >&2; exit 2 ;;
esac

python3 "$REPOSITORY_ROOT/infra/deploy/check_health.py" \
    --url "http://127.0.0.1:$port/actuator/health/readiness" \
    --deadline 60 --interval 2 --timeout 5 --required-successes 3

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
install -d -o root -g root -m 0750 "$BACKUP_ROOT"
backup_dir="$(mktemp -d "$BACKUP_ROOT/nginx-backend-route-$timestamp.XXXXXX")"
chown root:root "$backup_dir"
chmod 0750 "$backup_dir"
install -d -o root -g root -m 0755 "$NGINX_SLOT_DIR"

install -o root -g root -m 0644 \
    "$REPOSITORY_ROOT/infra/nginx/backend-slots/blue.conf" "$NGINX_SLOT_DIR/blue.conf"
install -o root -g root -m 0644 \
    "$REPOSITORY_ROOT/infra/nginx/backend-slots/green.conf" "$NGINX_SLOT_DIR/green.conf"

if [ -f "$NGINX_SITE" ]; then
    install -o root -g root -m 0644 "$NGINX_SITE" "$backup_dir/micon-https.conf"
    had_site=true
else
    had_site=false
fi

previous_slot=
if [ -L "$NGINX_ACTIVE" ]; then
    previous_target="$(readlink "$NGINX_ACTIVE")"
    case "$previous_target" in
        "$NGINX_SLOT_DIR/blue.conf") previous_slot=blue ;;
        "$NGINX_SLOT_DIR/green.conf") previous_slot=green ;;
        *) echo "refusing unknown active backend target" >&2; exit 1 ;;
    esac
fi

restore_previous() {
    if [ "$had_site" = true ]; then
        install -o root -g root -m 0644 "$backup_dir/micon-https.conf" "$NGINX_SITE"
    else
        rm -f "$NGINX_SITE"
    fi
    if [ -n "$previous_slot" ]; then
        ln -sfn "$NGINX_SLOT_DIR/$previous_slot.conf" "$NGINX_ACTIVE.next"
        mv -Tf "$NGINX_ACTIVE.next" "$NGINX_ACTIVE"
    else
        rm -f "$NGINX_ACTIVE"
    fi
}

install -o root -g root -m 0644 "$REPOSITORY_ROOT/infra/nginx/micon-https.conf" "$NGINX_SITE"
ln -sfn "$NGINX_SLOT_DIR/$slot.conf" "$NGINX_ACTIVE.next"
mv -Tf "$NGINX_ACTIVE.next" "$NGINX_ACTIVE"

if ! nginx -t; then
    restore_previous
    nginx -t
    echo "nginx configuration rejected; previous route restored" >&2
    exit 1
fi

if ! systemctl reload nginx.service; then
    restore_previous
    nginx -t
    systemctl reload nginx.service
    echo "nginx reload failed; previous route restored" >&2
    exit 1
fi

smoke_body="$backup_dir/public-smoke.json"
if ! public_status="$(curl --silent --show-error --max-time 10 \
    --output "$smoke_body" --write-out '%{http_code}' \
    "https://$DOMAIN/api/v1/songs")"; then
    restore_previous
    nginx -t
    systemctl reload nginx.service
    rm -f "$smoke_body"
    echo "public API smoke request failed; previous route restored" >&2
    exit 1
fi
if [ "$public_status" != 401 ] || ! grep -q '"code":"UNAUTHENTICATED"' "$smoke_body"; then
    restore_previous
    nginx -t
    systemctl reload nginx.service
    rm -f "$smoke_body"
    echo "public API smoke failed; previous route restored" >&2
    exit 1
fi
rm -f "$smoke_body"

printf 'nginx_backend result=active slot=%s port=%s previous=%s backup=%s\n' \
    "$slot" "$port" "${previous_slot:-bootstrap}" "$backup_dir"
