#!/bin/sh
set -eu

load_secret() {
    variable="$1"
    file_variable="${variable}_FILE"
    file_path="$(printenv "$file_variable" 2>/dev/null || true)"

    if [ -z "$file_path" ]; then
        echo "recommendation startup failed: missing secret file setting for $variable" >&2
        exit 1
    fi
    if [ ! -r "$file_path" ]; then
        echo "recommendation startup failed: unreadable secret file for $variable" >&2
        exit 1
    fi

    value="$(cat "$file_path")"
    if [ -z "$value" ]; then
        echo "recommendation startup failed: empty secret file for $variable" >&2
        exit 1
    fi
    export "$variable=$value"
    unset "$file_variable"
}

load_secret AI_SERVICE_TOKEN

exec "$@"
