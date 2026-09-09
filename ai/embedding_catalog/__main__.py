"""Run with python -m ai.embedding_catalog from the repository root."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys

import pymysql
from botocore.exceptions import BotoCoreError, ClientError

from .contract import MAX_MANIFEST_BYTES, json_bytes, parse_json, require
from .loader import CatalogCache, atomic_write, build_recommender_index, fetch_snapshot
from .prepare import prepare, read_bounded, upload
from .registry import Registry
from .storage import S3Store
from .validation import validate_remote


def object_store():
    return S3Store(os.environ.get("MICON_CATALOG_BUCKET", ""),
                   os.environ.get("MICON_CATALOG_REGION", "ap-northeast-2"),
                   prefix=os.environ.get("MICON_CATALOG_PREFIX", "catalog/"))


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
    return pymysql.connect(host=host, user=user, password=password,
        port=int(os.environ.get("MICON_CATALOG_DB_PORT", "3306")),
        database=os.environ.get("MICON_CATALOG_DB_NAME", "micon"), charset="utf8mb4",
        autocommit=True, connect_timeout=10, read_timeout=60, write_timeout=60, **arguments)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    package = commands.add_parser("prepare", help="Package paired NPY files with explicit catalog IDs")
    package.add_argument("--csv", type=Path, required=True)
    package.add_argument("--models", type=Path, required=True)
    package.add_argument("--owner", required=True)
    package.add_argument("--batch-id", required=True)
    package.add_argument("--output", type=Path, required=True)
    publish = commands.add_parser("upload", help="Upload immutable artifacts, then the completion manifest")
    publish.add_argument("--directory", type=Path, required=True)
    publish.add_argument("--dry-run", action="store_true")
    for command in ("validate", "register"):
        entry = commands.add_parser(command)
        entry.add_argument("--manifest-key", required=True)
        entry.add_argument("--manifest-sha256", required=True)
    activate = commands.add_parser("activate", help="Activate or roll back to a previously registered batch")
    activate.add_argument("--batch-id", required=True)
    activate.add_argument("--expected-revision", required=True, type=int)
    activate.add_argument("--actor", required=True)
    snapshot = commands.add_parser("snapshot", help="Export the same snapshot served by the backend")
    snapshot.add_argument("--output", type=Path, required=True)
    refresh = commands.add_parser("refresh", help="Fetch/cache the active snapshot for a recommendation worker")
    source = refresh.add_mutually_exclusive_group(required=True)
    source.add_argument("--snapshot", type=Path)
    source.add_argument("--backend-url")
    refresh.add_argument("--models", type=Path, required=True, help="The worker's expected model contracts")
    refresh.add_argument("--cache", type=Path, required=True)
    refresh.add_argument("--recommender-output", type=Path,
                         help="Optional NEW directory; installed timbre_recommender builds its existing index format")
    args = parser.parse_args(argv)
    if args.command == "prepare":
        path = prepare(args.csv, parse_json(read_bounded(args.models, MAX_MANIFEST_BYTES)),
                       args.owner, args.batch_id, args.output)
        result = {"manifest": str(path)}
    elif args.command == "upload":
        result = upload(args.directory, None if args.dry_run else object_store(), dry_run=args.dry_run)
    elif args.command == "validate":
        manifest, _ = validate_remote(object_store(), args.manifest_key, args.manifest_sha256)
        result = {"batch_id": manifest["batch_id"], "tracks": len(manifest["tracks"]), "valid": True}
    elif args.command in ("register", "activate", "snapshot"):
        with database() as connection:
            registry = Registry(connection)
            if args.command == "register":
                result = registry.register(object_store(), args.manifest_key, args.manifest_sha256)
            elif args.command == "activate":
                result = registry.activate(object_store(), args.batch_id, args.expected_revision, args.actor)
            else:
                result = registry.snapshot()
                atomic_write(args.output, json_bytes(result))
                result = {"snapshot": str(args.output), "batch_id": result["batch_id"], "revision": result["revision"]}
    else:
        payload = parse_json(read_bounded(args.snapshot, 16 * 1024 * 1024)) if args.snapshot else fetch_snapshot(
            args.backend_url, os.environ.get("AI_SERVICE_TOKEN", ""))
        models = parse_json(read_bounded(args.models, MAX_MANIFEST_BYTES))
        cache = CatalogCache(object_store(), args.cache, models)
        catalog = cache.refresh(payload)
        result = {"batch_id": catalog.snapshot["batch_id"], "revision": catalog.snapshot["revision"],
                  "tracks": len(catalog.arrays)}
        if args.recommender_output:
            result["index"] = str(build_recommender_index(catalog, args.recommender_output))
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, pymysql.MySQLError, BotoCoreError, ClientError) as error:
        print(f"Catalog operation failed: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
