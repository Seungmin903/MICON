"""Validate and register the approved 384-song vocal-stem mapping."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys

import pymysql
from botocore.exceptions import BotoCoreError, ClientError

from ai.embedding_catalog.prepare import read_bounded
from .contract import read_mapping, require
from .registry import CatalogMediaRegistry
from .storage import CatalogMediaStore


def database():
    host, user, password_file = (os.environ.get(key, "") for key in
        ("MICON_CATALOG_DB_HOST", "MICON_CATALOG_DB_USER", "MICON_CATALOG_DB_PASSWORD_FILE"))
    require(host and user and password_file, "DB host/user/password-file settings are required")
    password = read_bounded(Path(password_file), 4096).decode("utf-8").rstrip("\r\n")
    require(bool(password), "Database password file is empty")
    arguments = {}
    ca_file = os.environ.get("MICON_CATALOG_DB_CA_FILE")
    if ca_file:
        arguments["ssl"] = {"ca": ca_file, "check_hostname": True}
    return pymysql.connect(host=host, port=int(os.environ.get("MICON_CATALOG_DB_PORT", "3306")),
        user=user, password=password, database=os.environ.get("MICON_CATALOG_DB_NAME", "micon"),
        charset="utf8mb4", autocommit=True, connect_timeout=10, read_timeout=60,
        write_timeout=60, **arguments)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate", "register"))
    parser.add_argument("--mapping", required=True, type=Path)
    parser.add_argument("--mapping-sha256", required=True)
    parser.add_argument("--expected-count", type=int, default=384)
    args = parser.parse_args(argv)
    rows = read_mapping(args.mapping, args.mapping_sha256, args.expected_count)
    result = {"mapping_rows": len(rows), "mapping_valid": True}
    if args.command == "register":
        store = CatalogMediaStore(os.environ.get("MICON_CATALOG_BUCKET", ""),
                                  os.environ.get("MICON_CATALOG_REGION", "ap-northeast-2"))
        store.verify_heads(rows)
        with database() as connection:
            result.update(CatalogMediaRegistry(connection).register(rows))
        result["s3_heads_verified"] = len(rows)
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, pymysql.MySQLError, BotoCoreError, ClientError) as error:
        print(f"Catalog media operation failed: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
