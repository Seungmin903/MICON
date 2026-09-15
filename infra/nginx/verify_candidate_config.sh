#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
temporary_dir="$(mktemp -d /tmp/micon-nginx-candidate.XXXXXX)"
candidate_site="$temporary_dir/micon-https.conf"
candidate_main="$temporary_dir/nginx.conf"
candidate_pid="$temporary_dir/nginx.pid"

cleanup() {
    rm -f "$candidate_site" "$candidate_main" "$candidate_pid"
    rmdir "$temporary_dir"
}
trap cleanup EXIT

sed 's@include /etc/nginx/micon/backend-active.conf;@server 127.0.0.1:18081;@' \
    "$SCRIPT_DIR/micon-https.conf" > "$candidate_site"
sed \
    -e "s@__MICON_CANDIDATE_PID__@$candidate_pid@" \
    -e "s@__MICON_CANDIDATE_SITE__@$candidate_site@" \
    "$SCRIPT_DIR/nginx-candidate-main.conf" > "$candidate_main"

nginx -t -p "$temporary_dir/" -c "$candidate_main"
