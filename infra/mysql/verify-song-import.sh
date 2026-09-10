#!/usr/bin/env sh
set -eu

# Test-only: the verifier refuses nonempty schemas and never cleans a database.
: "${MICON_MYSQL_TEST:?Set MICON_MYSQL_TEST=1 for an isolated disposable MySQL}"
: "${MICON_TEST_DB_PASSWORD:?Set the disposable MySQL root password}"
cd "$(dirname "$0")/../../backend"
exec sh ./gradlew --no-daemon --max-workers=1 --console=plain verifySongImport
