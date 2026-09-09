#!/bin/sh
set -eu

BACKEND_UID=10001
BACKEND_GID=10001
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPOSITORY_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
SOURCE_SECRET_DIR=/etc/micon/secrets
BACKEND_SECRET_DIR=/etc/micon/backend-secrets
RUNTIME_DIR=/etc/micon/runtime

if [ "$(id -u)" -ne 0 ]; then
    echo "prepare_backend_host.sh must run as root" >&2
    exit 1
fi

require_secret() {
    secret_name="$1"
    if [ ! -s "$SOURCE_SECRET_DIR/$secret_name" ]; then
        echo "missing required source secret: $secret_name" >&2
        exit 1
    fi
}

create_secret_if_missing() {
    secret_name="$1"
    if [ ! -s "$SOURCE_SECRET_DIR/$secret_name" ]; then
        umask 077
        openssl rand -hex 32 > "$SOURCE_SECRET_DIR/$secret_name"
    fi
    chown root:root "$SOURCE_SECRET_DIR/$secret_name"
    chmod 0600 "$SOURCE_SECRET_DIR/$secret_name"
}

stage_backend_secret() {
    secret_name="$1"
    install \
        -o "$BACKEND_UID" \
        -g "$BACKEND_GID" \
        -m 0400 \
        "$SOURCE_SECRET_DIR/$secret_name" \
        "$BACKEND_SECRET_DIR/$secret_name"
}

install -d -o root -g root -m 0700 "$SOURCE_SECRET_DIR" "$RUNTIME_DIR"
install -d -o root -g root -m 0700 "$BACKEND_SECRET_DIR"

require_secret mysql_app_password
require_secret mysql_migration_password
require_secret aws_credentials
create_secret_if_missing jwt_secret
create_secret_if_missing ai_service_token

for secret_name in mysql_app_password jwt_secret ai_service_token aws_credentials; do
    stage_backend_secret "$secret_name"
done

if [ ! -e "$RUNTIME_DIR/backend.env" ]; then
    install \
        -o root \
        -g root \
        -m 0600 \
        "$REPOSITORY_ROOT/infra/compose/backend.env.example" \
        "$RUNTIME_DIR/backend.env"
fi

if [ ! -e "$RUNTIME_DIR/backend-deploy.env" ]; then
    install \
        -o root \
        -g root \
        -m 0600 \
        "$REPOSITORY_ROOT/infra/deploy/backend-deploy.env.example" \
        "$RUNTIME_DIR/backend-deploy.env"
fi

echo "backend host prerequisites are ready"
