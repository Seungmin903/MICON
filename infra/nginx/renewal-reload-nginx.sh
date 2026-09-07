#!/bin/sh
set -eu

# Certbot runs deploy hooks as root after successful certificate renewal.
case "${RENEWED_LINEAGE:-}" in
    /etc/letsencrypt/live/j15b110.p.ssafy.io)
        /usr/sbin/nginx -t
        /usr/bin/systemctl reload nginx.service
        /usr/bin/logger -t micon-certbot 'Nginx reloaded after certificate renewal'
        ;;
esac
