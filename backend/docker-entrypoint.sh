#!/bin/sh
set -eu

load_secret() {
    variable="$1"
    required="$2"
    file_variable="${variable}_FILE"
    file_path="$(printenv "$file_variable" 2>/dev/null || true)"

    if [ -z "$file_path" ]; then
        if [ "$required" = required ]; then
            echo "backend startup failed: missing secret file setting for $variable" >&2
            exit 1
        fi
        return
    fi
    if [ ! -r "$file_path" ]; then
        echo "backend startup failed: unreadable secret file for $variable" >&2
        exit 1
    fi

    value="$(cat "$file_path")"
    if [ -z "$value" ]; then
        echo "backend startup failed: empty secret file for $variable" >&2
        exit 1
    fi
    export "$variable=$value"
    unset "$file_variable"
}

load_secret DB_PASSWORD required
load_secret JWT_SECRET required
load_secret AI_SERVICE_TOKEN required
load_secret AI_RECOMMENDATION_SERVICE_TOKEN required

exec java ${JAVA_OPTS:--XX:MaxRAMPercentage=75.0} -jar /app/backend.jar
